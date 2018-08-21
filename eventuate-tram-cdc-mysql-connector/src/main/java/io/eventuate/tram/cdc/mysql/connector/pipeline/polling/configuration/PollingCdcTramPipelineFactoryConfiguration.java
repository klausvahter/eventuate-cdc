package io.eventuate.tram.cdc.mysql.connector.pipeline.polling.configuration;

import io.eventuate.local.java.common.broker.DataProducerFactory;
import io.eventuate.tram.cdc.mysql.connector.pipeline.polling.factory.PollingCdcTramPipelineFactory;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PollingCdcTramPipelineFactoryConfiguration {
  @Bean("evenutateTramPolling")
  public PollingCdcTramPipelineFactory pollingCdcPipelineFactory(CuratorFramework curatorFramework,
                                                                 DataProducerFactory dataProducerFactory) {

    return new PollingCdcTramPipelineFactory(curatorFramework, dataProducerFactory);
  }
}
