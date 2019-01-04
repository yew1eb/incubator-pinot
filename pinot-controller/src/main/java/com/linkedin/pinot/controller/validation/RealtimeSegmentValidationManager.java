/**
 * Copyright (C) 2014-2018 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.controller.validation;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.linkedin.pinot.common.config.TableConfig;
import com.linkedin.pinot.common.config.TableNameBuilder;
import com.linkedin.pinot.common.metadata.segment.RealtimeSegmentZKMetadata;
import com.linkedin.pinot.common.metrics.ValidationMetrics;
import com.linkedin.pinot.common.utils.CommonConstants;
import com.linkedin.pinot.common.utils.HLCSegmentName;
import com.linkedin.pinot.common.utils.SegmentName;
import com.linkedin.pinot.controller.ControllerConf;
import com.linkedin.pinot.controller.helix.core.PinotHelixResourceManager;
import com.linkedin.pinot.controller.helix.core.periodictask.ControllerPeriodicTask;
import com.linkedin.pinot.controller.helix.core.realtime.PinotLLCRealtimeSegmentManager;
import com.linkedin.pinot.core.realtime.stream.StreamConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Validates realtime ideal states and segment metadata, fixing any partitions which have stopped consuming
 */
public class RealtimeSegmentValidationManager extends ControllerPeriodicTask {
  private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeSegmentValidationManager.class);

  protected final PinotLLCRealtimeSegmentManager _llcRealtimeSegmentManager;
  protected final ValidationMetrics _validationMetrics;

  private final int _segmentLevelValidationIntervalInSeconds;
  private long _lastUpdateRealtimeDocumentCountTimeMs = 0L;
  private boolean _updateRealtimeDocumentCount;

  public RealtimeSegmentValidationManager(ControllerConf config, PinotHelixResourceManager pinotHelixResourceManager,
      PinotLLCRealtimeSegmentManager llcRealtimeSegmentManager, ValidationMetrics validationMetrics) {
    super("RealtimeSegmentValidationManager", config.getRealtimeSegmentValidationFrequencyInSeconds(),
        pinotHelixResourceManager);
    _llcRealtimeSegmentManager = llcRealtimeSegmentManager;
    _validationMetrics = validationMetrics;

    _segmentLevelValidationIntervalInSeconds = config.getSegmentLevelValidationIntervalInSeconds();
    Preconditions.checkState(_segmentLevelValidationIntervalInSeconds > 0);
  }

  @Override
  protected void preprocess() {
    // Update realtime document counts only if certain time has passed after previous run
    _updateRealtimeDocumentCount = false;
    long currentTimeMs = System.currentTimeMillis();
    if (TimeUnit.MILLISECONDS.toSeconds(currentTimeMs - _lastUpdateRealtimeDocumentCountTimeMs)
        >= _segmentLevelValidationIntervalInSeconds) {
      LOGGER.info("Run segment-level validation");
      _updateRealtimeDocumentCount = true;
      _lastUpdateRealtimeDocumentCountTimeMs = currentTimeMs;
    }
  }

  @Override
  protected void processTable(String tableNameWithType) {
    try {
      TableConfig tableConfig = _pinotHelixResourceManager.getTableConfig(tableNameWithType);
      if (tableConfig == null) {
        LOGGER.warn("Failed to find table config for table: {}, skipping validation", tableNameWithType);
        return;
      }

      CommonConstants.Helix.TableType tableType = TableNameBuilder.getTableTypeFromTableName(tableNameWithType);
      if (tableType == CommonConstants.Helix.TableType.REALTIME) {
        if (_updateRealtimeDocumentCount) {
          updateRealtimeDocumentCount(tableConfig);
        }
      }
      Map<String, String> streamConfigMap = tableConfig.getIndexingConfig().getStreamConfigs();
      StreamConfig streamConfig = new StreamConfig(streamConfigMap);
      if (streamConfig.hasLowLevelConsumerType()) {
        _llcRealtimeSegmentManager.validateLLCSegments(tableConfig);
      }
    } catch (Exception e) {
      LOGGER.warn("Caught exception while validating realtime table: {}", tableNameWithType, e);
    }
  }

  private void updateRealtimeDocumentCount(TableConfig tableConfig) {
    String realtimeTableName = tableConfig.getTableName();
    List<RealtimeSegmentZKMetadata> metadataList =
        _pinotHelixResourceManager.getRealtimeSegmentMetadata(realtimeTableName);
    boolean countHLCSegments = true;  // false if this table has ONLY LLC segments (i.e. fully migrated)
    StreamConfig streamConfig = new StreamConfig(tableConfig.getIndexingConfig().getStreamConfigs());
    if (streamConfig.hasLowLevelConsumerType() && !streamConfig.hasHighLevelConsumerType()) {
      countHLCSegments = false;
    }
    // Update the gauge to contain the total document count in the segments
    _validationMetrics.updateTotalDocumentCountGauge(tableConfig.getTableName(),
        computeRealtimeTotalDocumentInSegments(metadataList, countHLCSegments));
  }

  @VisibleForTesting
  static long computeRealtimeTotalDocumentInSegments(List<RealtimeSegmentZKMetadata> realtimeSegmentZKMetadataList,
      boolean countHLCSegments) {
    long numTotalDocs = 0;

    String groupId = "";
    for (RealtimeSegmentZKMetadata realtimeSegmentZKMetadata : realtimeSegmentZKMetadataList) {
      String segmentName = realtimeSegmentZKMetadata.getSegmentName();
      if (SegmentName.isHighLevelConsumerSegmentName(segmentName)) {
        if (countHLCSegments) {
          HLCSegmentName hlcSegmentName = new HLCSegmentName(segmentName);
          String segmentGroupIdName = hlcSegmentName.getGroupId();

          if (groupId.isEmpty()) {
            groupId = segmentGroupIdName;
          }
          // Discard all segments with different groupids as they are replicas
          if (groupId.equals(segmentGroupIdName) && realtimeSegmentZKMetadata.getTotalRawDocs() >= 0) {
            numTotalDocs += realtimeSegmentZKMetadata.getTotalRawDocs();
          }
        }
      } else {
        // Low level segments
        if (!countHLCSegments) {
          numTotalDocs += realtimeSegmentZKMetadata.getTotalRawDocs();
        }
      }
    }

    return numTotalDocs;
  }

  @Override
  protected void postprocess() {

  }

  @Override
  protected void initTask() {

  }

  @Override
  public void stopTask() {
    LOGGER.info("Unregister all the validation metrics.");
    _validationMetrics.unregisterAllMetrics();
  }
}