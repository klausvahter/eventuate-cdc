package io.eventuate.tram.cdc.mysql.connector.pipeline.mysqlbinlog.configuration;

import io.eventuate.local.common.MySqlBinlogCondition;
import io.eventuate.local.db.log.common.PublishingFilter;
import io.eventuate.local.java.common.broker.DataProducerFactory;
import io.eventuate.local.java.kafka.EventuateKafkaConfigurationProperties;
import io.eventuate.local.java.kafka.consumer.EventuateKafkaConsumerConfigurationProperties;
import io.eventuate.local.java.kafka.producer.EventuateKafkaProducer;
import io.eventuate.local.unified.cdc.pipeline.common.factory.CdcPipelineFactory;
import io.eventuate.tram.cdc.mysql.connector.MysqlBinLogOffsetStoreFactory;
import io.eventuate.tram.cdc.mysql.connector.pipeline.mysqlbinlog.factory.MySqlCdcTramPipelineFactory;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MySqlBinlogDefaultCdcTramPipelineFactoryConfiguration {
  @Conditional(MySqlBinlogCondition.class)
  @Primary
  @Bean("default")
  public CdcPipelineFactory defaultMySqlCdcTramPipelineFactory(CuratorFramework curatorFramework,
                                                               DataProducerFactory dataProducerFactory,
                                                               EventuateKafkaConfigurationProperties eventuateKafkaConfigurationProperties,
                                                               EventuateKafkaConsumerConfigurationProperties eventuateKafkaConsumerConfigurationProperties,
                                                               EventuateKafkaProducer eventuateKafkaProducer,
                                                               MysqlBinLogOffsetStoreFactory mysqlBinLogOffsetStoreFactory,
                                                               PublishingFilter publishingFilter) {

    return new MySqlCdcTramPipelineFactory(curatorFramework,
            dataProducerFactory,
            eventuateKafkaConfigurationProperties,
            eventuateKafkaConsumerConfigurationProperties,
            eventuateKafkaProducer,
            mysqlBinLogOffsetStoreFactory,
            publishingFilter);
  }
}
