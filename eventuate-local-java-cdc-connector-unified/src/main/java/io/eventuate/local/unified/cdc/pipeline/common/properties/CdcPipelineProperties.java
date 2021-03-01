package io.eventuate.local.unified.cdc.pipeline.common.properties;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.commons.lang.builder.ReflectionToStringBuilder;
import org.springframework.util.Assert;

import java.util.concurrent.TimeUnit;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CdcPipelineProperties implements ValidatableProperties {
  private String type;

  private String reader;
  private String eventuateDatabaseSchema = null;
  private String sourceTableName = null;

  private Boolean purgeMessagesEnabled = false;
  private int purgeMessagesMaxAgeInSeconds = (int)TimeUnit.DAYS.toSeconds(2);
  private Boolean purgeReceivedMessagesEnabled = false;
  private int purgeReceivedMessagesMaxAgeInSeconds = (int)TimeUnit.DAYS.toSeconds(2);

  private int purgeIntervalInSeconds = (int)TimeUnit.MINUTES.toSeconds(1);

  public void validate() {
    Assert.notNull(type, "type must not be null");
    Assert.notNull(reader, "reader must not be null");
  }

  public String getReader() {
    return reader;
  }

  public void setReader(String reader) {
    this.reader = reader;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getEventuateDatabaseSchema() {
    return eventuateDatabaseSchema;
  }

  public void setEventuateDatabaseSchema(String eventuateDatabaseSchema) {
    this.eventuateDatabaseSchema = eventuateDatabaseSchema;
  }

  @Override
  public String toString() {
    return ReflectionToStringBuilder.toString(this);
  }

  public String getSourceTableName() {
    return sourceTableName;
  }

  public void setSourceTableName(String sourceTableName) {
    this.sourceTableName = sourceTableName;
  }

  public Boolean getPurgeMessagesEnabled() {
    return purgeMessagesEnabled;
  }

  public void setPurgeMessagesEnabled(Boolean purgeMessagesEnabled) {
    this.purgeMessagesEnabled = purgeMessagesEnabled;
  }

  public int getPurgeMessagesMaxAgeInSeconds() {
    return purgeMessagesMaxAgeInSeconds;
  }

  public void setPurgeMessagesMaxAgeInSeconds(int purgeMessagesMaxAgeInSeconds) {
    this.purgeMessagesMaxAgeInSeconds = purgeMessagesMaxAgeInSeconds;
  }

  public Boolean getPurgeReceivedMessagesEnabled() {
    return purgeReceivedMessagesEnabled;
  }

  public void setPurgeReceivedMessagesEnabled(Boolean purgeReceivedMessagesEnabled) {
    this.purgeReceivedMessagesEnabled = purgeReceivedMessagesEnabled;
  }

  public int getPurgeReceivedMessagesMaxAgeInSeconds() {
    return purgeReceivedMessagesMaxAgeInSeconds;
  }

  public void setPurgeReceivedMessagesMaxAgeInSeconds(int purgeReceivedMessagesMaxAgeInSeconds) {
    this.purgeReceivedMessagesMaxAgeInSeconds = purgeReceivedMessagesMaxAgeInSeconds;
  }

  public int getPurgeIntervalInSeconds() {
    return purgeIntervalInSeconds;
  }

  public void setPurgeIntervalInSeconds(int purgeIntervalInSeconds) {
    this.purgeIntervalInSeconds = purgeIntervalInSeconds;
  }
}
