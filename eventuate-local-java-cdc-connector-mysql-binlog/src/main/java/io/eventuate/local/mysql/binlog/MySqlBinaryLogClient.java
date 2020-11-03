package io.eventuate.local.mysql.binlog;


import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.NullEventDataDeserializer;
import com.google.common.collect.ImmutableSet;
import io.eventuate.common.eventuate.local.BinlogFileOffset;
import io.eventuate.common.jdbc.EventuateSchema;
import io.eventuate.local.common.*;
import io.eventuate.local.db.log.common.DbLogClient;
import io.eventuate.local.db.log.common.OffsetKafkaStore;
import io.eventuate.local.db.log.common.OffsetStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MySqlBinaryLogClient extends DbLogClient {

  private static final Set<EventType> SUPPORTED_EVENTS = ImmutableSet.of(EventType.TABLE_MAP,
          EventType.ROTATE,
          EventType.WRITE_ROWS,
          EventType.EXT_WRITE_ROWS,
          EventType.UPDATE_ROWS,
          EventType.EXT_UPDATE_ROWS);

  private Long uniqueId;
  private BinaryLogClient client;
  private final Map<Long, TableMapEventData> tableMapEventByTableId = new HashMap<>();
  private String binlogFilename;
  private MySqlBinlogEntryExtractor extractor;
  private MySqlBinlogCdcMonitoringTimestampExtractor timestampExtractor;
  private int connectionTimeoutInMilliseconds;
  private int maxAttemptsForBinlogConnection;
  private Optional<DebeziumBinlogOffsetKafkaStore> debeziumBinlogOffsetKafkaStore;
  private int rowsToSkip;
  private OffsetStore offsetStore;

  private Optional<Long> cdcMonitoringTableId = Optional.empty();
  private MySqlCdcProcessingStatusService mySqlCdcProcessingStatusService;
  private Optional<Exception> publishingException = Optional.empty();

  private Optional<Runnable> callbackOnStop = Optional.empty();
  private OffsetProcessor<BinlogFileOffset> offsetProcessor;
  private Long eventProcessingStartTime;

  private AtomicLong timeOfFirstMessage = new AtomicLong();
  private AtomicLong timeOfLatestMessage = new AtomicLong();;
  private Timer messagePublishingTimer;
  private BinaryLogClient.EventListener eventListener;

  public MySqlBinaryLogClient(MeterRegistry meterRegistry,
                              String dbUserName,
                              String dbPassword,
                              String dataSourceUrl,
                              DataSource dataSource,
                              String readerName,
                              Long uniqueId,
                              int connectionTimeoutInMilliseconds,
                              int maxAttemptsForBinlogConnection,
                              OffsetStore offsetStore,
                              Optional<DebeziumBinlogOffsetKafkaStore> debeziumBinlogOffsetKafkaStore,
                              long replicationLagMeasuringIntervalInMilliseconds,
                              int monitoringRetryIntervalInMilliseconds,
                              int monitoringRetryAttempts,
                              EventuateSchema monitoringSchema,
                              Long outboxId) {

    super(meterRegistry,
            dbUserName,
            dbPassword,
            dataSourceUrl,
            dataSource,
            readerName,
            replicationLagMeasuringIntervalInMilliseconds,
            monitoringRetryIntervalInMilliseconds,
            monitoringRetryAttempts,
            monitoringSchema,
            outboxId);

    this.extractor = new MySqlBinlogEntryExtractor(dataSource);
    this.timestampExtractor = new MySqlBinlogCdcMonitoringTimestampExtractor(dataSource);
    this.uniqueId = uniqueId;
    this.connectionTimeoutInMilliseconds = connectionTimeoutInMilliseconds;
    this.maxAttemptsForBinlogConnection = maxAttemptsForBinlogConnection;
    this.offsetStore = offsetStore;
    this.debeziumBinlogOffsetKafkaStore = debeziumBinlogOffsetKafkaStore;

    offsetProcessor = new OffsetProcessor<>(offsetStore);

    mySqlCdcProcessingStatusService = new MySqlCdcProcessingStatusService(dataSourceUrl, dbUserName, dbPassword);

    meterRegistry.gauge("eventuate.cdc.mysql.event.unprocessed.offsets", offsetProcessor.getUnprocessedOffsetCount());

    meterRegistry.gauge("eventuate.cdc.mysql.event.first.message.time", timeOfFirstMessage);
    meterRegistry.gauge("eventuate.cdc.mysql.event.latest.message.time", timeOfLatestMessage);

    messagePublishingTimer = meterRegistry.timer("eventuate.cdc.mysql.message.publishing.duration");
  }

  public Long getEventProcessingStartTime() {
    return eventProcessingStartTime;
  }

  public Optional<Exception> getPublishingException() {
    return publishingException;
  }

  public Optional<MigrationInfo> getMigrationInfo() {
    if (offsetStore.getLastBinlogFileOffset().isPresent()) {
      return Optional.empty();
    }

    return debeziumBinlogOffsetKafkaStore
            .flatMap(OffsetKafkaStore::getLastBinlogFileOffset)
            .map(MigrationInfo::new);
  }

  @Override
  public CdcProcessingStatusService getCdcProcessingStatusService() {
    return mySqlCdcProcessingStatusService;
  }

  @Override
  public void start() {
    logger.info("Starting MySqlBinaryLogClient");
    super.start();
    stopCountDownLatch = new CountDownLatch(1);
    running.set(true);
    publishingException = Optional.empty();

    client = new BinaryLogClient(host, port, dbUserName, dbPassword);
    client.setServerId(uniqueId);
    client.setKeepAliveInterval(5 * 1000);

    Optional<BinlogFileOffset> binlogFileOffset;

    try {
      binlogFileOffset = getStartingBinlogFileOffset();
    } catch (Exception e) {
      handleRestart(e);
      return;
    }

    BinlogFileOffset bfo = binlogFileOffset.orElse(new BinlogFileOffset("", 4L));
    rowsToSkip = bfo.getRowsToSkip();

    logger.info("mysql binlog starting offset {}", bfo);
    client.setBinlogFilename(bfo.getBinlogFilename());
    client.setBinlogPosition(bfo.getOffset());

    client.setEventDeserializer(getEventDeserializer());

    eventListener = event -> handleBinlogEventWithErrorHandling(event, binlogFileOffset);

    client.registerEventListener(eventListener);

    connectWithRetriesOnFail();

    try {
      stopCountDownLatch.await();
    } catch (InterruptedException e) {
      handleProcessingFailException(e);
    }
    logger.info("MySqlBinaryLogClient finished processing");
  }

  private void handleBinlogEventWithErrorHandling(Event event, Optional<BinlogFileOffset> binlogFileOffset) {
    if (publishingException.isPresent()) {
      return;
    }

    try {
      meterRegistry.timer("eventuate.cdc.mysql.event.processing.duration").record(() -> {
        handleBinlogEvent(event, binlogFileOffset);
      });
    } catch (Exception e) {
      handleRestart(e);
    }
  }

  private void handleRestart(Exception e) {
    logger.error("Restarting due to exception", e);
    publishingException = Optional.of(e);
    restartCallback
            .orElseThrow(() -> new IllegalArgumentException("Restart callback is not specified, but restart is requsted"))
            .run();
  }

  private void handleBinlogEvent(Event event, Optional<BinlogFileOffset> binlogFileOffset) {

    switch (event.getHeader().getEventType()) {
      case TABLE_MAP: {
        TableMapEventData tableMapEvent = event.getData();

        if (cdcMonitoringDao.isMonitoringTableChange(tableMapEvent.getDatabase(), tableMapEvent.getTable())) {
          cdcMonitoringTableId = Optional.of(tableMapEvent.getTableId());
          tableMapEventByTableId.put(tableMapEvent.getTableId(), tableMapEvent);
          break;
        }

        cdcMonitoringTableId = cdcMonitoringTableId.filter(id -> !id.equals(tableMapEvent.getTableId()));

        SchemaAndTable schemaAndTable = new SchemaAndTable(tableMapEvent.getDatabase(), tableMapEvent.getTable());

        boolean shouldHandleTable = binlogEntryHandlers
                .stream()
                .map(BinlogEntryHandler::getSchemaAndTable)
                .anyMatch(schemaAndTable::equals);

        if (shouldHandleTable) {
          tableMapEventByTableId.put(tableMapEvent.getTableId(), tableMapEvent);
          extractor.refreshColumnOrder();
        } else {
          tableMapEventByTableId.remove(tableMapEvent.getTableId());
        }

        dbLogMetrics.onBinlogEntryProcessed();

        break;
      }
      case EXT_WRITE_ROWS: {
        initProcessingInfo();
        handleWriteRowsEvent(event, binlogFileOffset);
        break;
      }
      case WRITE_ROWS: {
        initProcessingInfo();
        handleWriteRowsEvent(event, binlogFileOffset);
        break;
      }
      case EXT_UPDATE_ROWS: {
        handleUpdateRowsEvent(event);
        break;
      }
      case UPDATE_ROWS: {
        handleUpdateRowsEvent(event);
        break;
      }
      case ROTATE: {
        RotateEventData eventData = event.getData();
        if (eventData != null) {
          binlogFilename = eventData.getBinlogFilename();
        }
        break;
      }
    }

    saveEndingOffsetOfLastProcessedEvent(event);
  }

  private void initProcessingInfo() {
    if (eventProcessingStartTime == null) {
      eventProcessingStartTime = System.nanoTime();
      meterRegistry.gauge("eventuate.cdc.processing.start.time", eventProcessingStartTime);
    }
  }

  private Optional<BinlogFileOffset> getStartingBinlogFileOffset() {
    Optional<BinlogFileOffset> binlogFileOffset = offsetStore.getLastBinlogFileOffset();

    logger.info("mysql binlog client received offset from the offset store: {}", binlogFileOffset);

    if (!binlogFileOffset.isPresent()) {
      logger.info("mysql binlog client received empty offset from the offset store, retrieving debezium offset");
      binlogFileOffset = debeziumBinlogOffsetKafkaStore.flatMap(OffsetKafkaStore::getLastBinlogFileOffset);
      logger.info("mysql binlog client received offset from the debezium offset store: {}", binlogFileOffset);
    }

    return binlogFileOffset;
  }

  private void handleWriteRowsEvent(Event event, Optional<BinlogFileOffset> startingBinlogFileOffset) {
    if (rowsToSkip > 0) {
      rowsToSkip--;
      return;
    }

    logger.debug("Got binlog event {}", event);

    WriteRowsEventData eventData = event.getData();

    BinlogFileOffset binlogFileOffset = extractBinlogFileOffset(event);

    long offset = binlogFileOffset.getOffset();

    logger.debug("mysql binlog client got event with offset {}/{}", binlogFilename, offset);

    AtomicBoolean eventPublished = new AtomicBoolean(false);

    if (isCdcMonitoringTableId(eventData.getTableId())) {
      onLagMeasurementEventReceived(eventData);
    } else if (tableMapEventByTableId.containsKey(eventData.getTableId())) {
      TableMapEventData tableMapEventData = tableMapEventByTableId.get(eventData.getTableId());

      SchemaAndTable schemaAndTable = new SchemaAndTable(tableMapEventData.getDatabase(), tableMapEventData.getTable());

      BinlogEntry entry = extractor.extract(schemaAndTable, eventData, binlogFilename, offset);

      if (!shouldSkipEntry(startingBinlogFileOffset, entry.getBinlogFileOffset())) {
        binlogEntryHandlers
                .stream()
                .filter(bh -> bh.isFor(schemaAndTable))
                .forEach(binlogEntryHandler -> {
                  messagePublishingTimer.record(() -> {
                    publish(entry, binlogEntryHandler, binlogFileOffset);
                  });
                  eventPublished.set(true);
                });
      }
    }

    onEventReceived();

    if (!eventPublished.get()) {
      offsetProcessor.saveOffset(CompletableFuture.completedFuture(binlogFileOffset));
    }
  }

  private void publish(BinlogEntry entry, BinlogEntryHandler binlogEntryHandler, BinlogFileOffset binlogFileOffset) {
    long timeNow = System.currentTimeMillis();
    this.timeOfFirstMessage.compareAndSet(0, timeNow);
    this.timeOfLatestMessage.set(timeNow);

    CompletableFuture<?> publishingFuture = null;
    try {
      publishingFuture = binlogEntryHandler.publish(entry);
    } catch (Exception e) {
      handleProcessingFailException(e);
    }

    CompletableFuture<BinlogFileOffset> futureWithOffset = new CompletableFuture<>();

    publishingFuture.whenComplete((o, throwable) -> {
      if (throwable == null) {
        futureWithOffset.complete(binlogFileOffset);
      }
      else {
        futureWithOffset.completeExceptionally(throwable);
        handleProcessingFailException(throwable);
      }
    });

    offsetProcessor.saveOffset(futureWithOffset);
  }

  private BinlogFileOffset extractBinlogFileOffset(Event event) {
    return new BinlogFileOffset(binlogFilename, extractOffset(event));
  }

  private void onLagMeasurementEventReceived(WriteRowsEventData eventData) {
    dbLogMetrics.onLagMeasurementEventReceived(timestampExtractor.extract(cdcMonitoringDao.getMonitoringSchemaAndTable(), eventData));
  }

  private void handleUpdateRowsEvent(Event event) {
    UpdateRowsEventData eventData = event.getData();

    if (eventData == null) {
      return;
    }

    if (isCdcMonitoringTableId(eventData.getTableId())) {
      onLagMeasurementEventReceived(eventData);
    }

    onEventReceived();

    offsetProcessor.saveOffset(CompletableFuture.completedFuture(extractBinlogFileOffset(event)));
  }

  private void onLagMeasurementEventReceived(UpdateRowsEventData eventData) {
    dbLogMetrics.onLagMeasurementEventReceived(timestampExtractor.extract(cdcMonitoringDao.getMonitoringSchemaAndTable(), eventData));
  }

  private long extractOffset(Event event) {
    return ((EventHeaderV4) event.getHeader()).getPosition();
  }


  private boolean isCdcMonitoringTableId(Long id) {
    return cdcMonitoringTableId.map(id::equals).orElse(false);
  }

  private void connectWithRetriesOnFail() {
    for (int i = 1;; i++) {
      try {
        logger.info("trying to connect to mysql binlog");
        client.connect(connectionTimeoutInMilliseconds);
        onConnected();
        logger.info("connection to mysql binlog succeed");
        break;
      } catch (TimeoutException | IOException e) {
        onDisconnected();
        logger.error("connection to mysql binlog failed");
        if (i == maxAttemptsForBinlogConnection) {
          handleProcessingFailException(e);
        }
        try {
          Thread.sleep(connectionTimeoutInMilliseconds);
        } catch (InterruptedException ex) {
          handleProcessingFailException(ex);
        }
      }
      catch (Exception e) {
        handleProcessingFailException(e);
      }
    }
  }

  private EventDeserializer getEventDeserializer() {
    EventDeserializer eventDeserializer = new EventDeserializer();

    Arrays.stream(EventType.values()).forEach(eventType -> {
      if (!SUPPORTED_EVENTS.contains(eventType)) {
        eventDeserializer.setEventDataDeserializer(eventType,
                new NullEventDataDeserializer());
        eventDeserializer.setCompatibilityMode(EventDeserializer.CompatibilityMode.CHAR_AND_BINARY_AS_BYTE_ARRAY);
      }
    });

    eventDeserializer.setEventDataDeserializer(EventType.WRITE_ROWS,
            new WriteRowsDeserializer(tableMapEventByTableId, dbLogMetrics));

    eventDeserializer.setEventDataDeserializer(EventType.EXT_WRITE_ROWS,
            new WriteRowsDeserializer(tableMapEventByTableId, dbLogMetrics).setMayContainExtraInformation(true));

    eventDeserializer.setEventDataDeserializer(EventType.UPDATE_ROWS,
            new UpdateRowsDeserializer(tableMapEventByTableId, dbLogMetrics));

    eventDeserializer.setEventDataDeserializer(EventType.EXT_UPDATE_ROWS,
            new UpdateRowsDeserializer(tableMapEventByTableId, dbLogMetrics).setMayContainExtraInformation(true));

    return eventDeserializer;
  }

  @Override
  public void stop(boolean removeHandlers) {
    logger.info("Stopping MySqlBinaryLogClient");

    if (!running.compareAndSet(true, false)) {
      return;
    }

    tableMapEventByTableId.clear();
    cdcMonitoringTableId = Optional.empty();

    client.unregisterEventListener(eventListener);

    try {
      client.disconnect();
    } catch (IOException e) {
      logger.error("Cannot stop the MySqlBinaryLogClient", e);
    }

    if (removeHandlers) {
      binlogEntryHandlers.clear();
    }
    stopMetrics();
    stopCountDownLatch.countDown();

    callbackOnStop.ifPresent(Runnable::run);

    logger.info("Stopped MySqlBinaryLogClient");
  }

  private void saveEndingOffsetOfLastProcessedEvent(Event event) {
    long position = ((EventHeaderV4) event.getHeader()).getNextPosition();
    if (mySqlCdcProcessingStatusService != null) {
      mySqlCdcProcessingStatusService.saveEndingOffsetOfLastProcessedEvent(position);
    }
  }

  public static class MigrationInfo {
    private BinlogFileOffset binlogFileOffset;

    public MigrationInfo(BinlogFileOffset binlogFileOffset) {

      this.binlogFileOffset = binlogFileOffset;
    }

    public BinlogFileOffset getBinlogFileOffset() {
      return binlogFileOffset;
    }
  }

}
