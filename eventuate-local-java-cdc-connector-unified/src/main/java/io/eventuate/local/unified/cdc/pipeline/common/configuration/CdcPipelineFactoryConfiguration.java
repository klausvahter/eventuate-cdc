package io.eventuate.local.unified.cdc.pipeline.common.configuration;

import io.eventuate.common.eventuate.local.PublishedEvent;
import io.eventuate.common.jdbc.sqldialect.SqlDialectSelector;
import io.eventuate.common.spring.jdbc.sqldialect.SqlDialectConfiguration;
import io.eventuate.local.common.*;
import io.eventuate.local.unified.cdc.pipeline.common.BinlogEntryReaderProvider;
import io.eventuate.local.unified.cdc.pipeline.common.factory.CdcPipelineFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(SqlDialectConfiguration.class)
public class CdcPipelineFactoryConfiguration {
  @Bean("eventuateLocalСdcPipelineFactory")
  public CdcPipelineFactory<PublishedEvent> defaultCdcPipelineFactory(BinlogEntryReaderProvider binlogEntryReaderProvider,
                                                                      CdcDataPublisher<PublishedEvent> cdcDataPublisher,
                                                                      SqlDialectSelector sqlDialectSelector) {

    return new CdcPipelineFactory<>("eventuate-local",
            binlogEntryReaderProvider,
            cdcDataPublisher,
            new BinlogEntryToPublishedEventConverter(),
            sqlDialectSelector);
  }
}
