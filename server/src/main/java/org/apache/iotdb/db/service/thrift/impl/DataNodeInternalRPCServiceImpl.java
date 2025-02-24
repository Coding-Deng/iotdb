/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.service.thrift.impl;

import org.apache.iotdb.common.rpc.thrift.TConfigNodeLocation;
import org.apache.iotdb.common.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.common.rpc.thrift.TDataNodeLocation;
import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.common.rpc.thrift.TFlushReq;
import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.common.rpc.thrift.TSetTTLReq;
import org.apache.iotdb.commons.cluster.NodeStatus;
import org.apache.iotdb.commons.conf.CommonDescriptor;
import org.apache.iotdb.commons.consensus.ConsensusGroupId;
import org.apache.iotdb.commons.consensus.DataRegionId;
import org.apache.iotdb.commons.consensus.SchemaRegionId;
import org.apache.iotdb.commons.exception.MetadataException;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.commons.trigger.TriggerInformation;
import org.apache.iotdb.commons.trigger.service.TriggerExecutableManager;
import org.apache.iotdb.commons.udf.service.UDFExecutableManager;
import org.apache.iotdb.commons.udf.service.UDFRegistrationService;
import org.apache.iotdb.confignode.rpc.thrift.TTriggerState;
import org.apache.iotdb.consensus.common.Peer;
import org.apache.iotdb.consensus.common.response.ConsensusGenericResponse;
import org.apache.iotdb.consensus.common.response.ConsensusReadResponse;
import org.apache.iotdb.consensus.exception.PeerNotInConsensusGroupException;
import org.apache.iotdb.db.auth.AuthorizerManager;
import org.apache.iotdb.db.client.ConfigNodeInfo;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.consensus.DataRegionConsensusImpl;
import org.apache.iotdb.db.consensus.SchemaRegionConsensusImpl;
import org.apache.iotdb.db.engine.StorageEngineV2;
import org.apache.iotdb.db.engine.cache.BloomFilterCache;
import org.apache.iotdb.db.engine.cache.ChunkCache;
import org.apache.iotdb.db.engine.cache.TimeSeriesMetadataCache;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.metadata.cache.DataNodeSchemaCache;
import org.apache.iotdb.db.metadata.schemaregion.SchemaEngine;
import org.apache.iotdb.db.metadata.template.ClusterTemplateManager;
import org.apache.iotdb.db.metadata.template.TemplateInternalRPCUpdateType;
import org.apache.iotdb.db.mpp.common.FragmentInstanceId;
import org.apache.iotdb.db.mpp.common.schematree.PathPatternTree;
import org.apache.iotdb.db.mpp.execution.fragment.FragmentInstanceInfo;
import org.apache.iotdb.db.mpp.execution.fragment.FragmentInstanceManager;
import org.apache.iotdb.db.mpp.execution.fragment.FragmentInstanceState;
import org.apache.iotdb.db.mpp.plan.analyze.ClusterPartitionFetcher;
import org.apache.iotdb.db.mpp.plan.planner.plan.FragmentInstance;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNodeType;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.write.ConstructSchemaBlackListNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.write.DeleteTimeSeriesNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.write.RollbackSchemaBlackListNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.DeleteDataNode;
import org.apache.iotdb.db.service.DataNode;
import org.apache.iotdb.db.service.RegionMigrateService;
import org.apache.iotdb.db.service.metrics.MetricService;
import org.apache.iotdb.db.service.metrics.enums.Metric;
import org.apache.iotdb.db.service.metrics.enums.Tag;
import org.apache.iotdb.db.trigger.service.TriggerManagementService;
import org.apache.iotdb.db.utils.SetThreadName;
import org.apache.iotdb.metrics.config.MetricConfigDescriptor;
import org.apache.iotdb.metrics.type.Gauge;
import org.apache.iotdb.metrics.utils.MetricLevel;
import org.apache.iotdb.mpp.rpc.thrift.IDataNodeRPCService;
import org.apache.iotdb.mpp.rpc.thrift.TCancelFragmentInstanceReq;
import org.apache.iotdb.mpp.rpc.thrift.TCancelPlanFragmentReq;
import org.apache.iotdb.mpp.rpc.thrift.TCancelQueryReq;
import org.apache.iotdb.mpp.rpc.thrift.TCancelResp;
import org.apache.iotdb.mpp.rpc.thrift.TConstructSchemaBlackListReq;
import org.apache.iotdb.mpp.rpc.thrift.TCreateDataRegionReq;
import org.apache.iotdb.mpp.rpc.thrift.TCreateFunctionRequest;
import org.apache.iotdb.mpp.rpc.thrift.TCreatePeerReq;
import org.apache.iotdb.mpp.rpc.thrift.TCreateSchemaRegionReq;
import org.apache.iotdb.mpp.rpc.thrift.TDeleteDataForDeleteTimeSeriesReq;
import org.apache.iotdb.mpp.rpc.thrift.TDeleteTimeSeriesReq;
import org.apache.iotdb.mpp.rpc.thrift.TDisableDataNodeReq;
import org.apache.iotdb.mpp.rpc.thrift.TDropFunctionRequest;
import org.apache.iotdb.mpp.rpc.thrift.TDropTriggerInstanceReq;
import org.apache.iotdb.mpp.rpc.thrift.TFetchFragmentInstanceStateReq;
import org.apache.iotdb.mpp.rpc.thrift.TFetchSchemaBlackListReq;
import org.apache.iotdb.mpp.rpc.thrift.TFetchSchemaBlackListResp;
import org.apache.iotdb.mpp.rpc.thrift.TFragmentInstanceStateResp;
import org.apache.iotdb.mpp.rpc.thrift.THeartbeatReq;
import org.apache.iotdb.mpp.rpc.thrift.THeartbeatResp;
import org.apache.iotdb.mpp.rpc.thrift.TInvalidateCacheReq;
import org.apache.iotdb.mpp.rpc.thrift.TInvalidateMatchedSchemaCacheReq;
import org.apache.iotdb.mpp.rpc.thrift.TInvalidatePermissionCacheReq;
import org.apache.iotdb.mpp.rpc.thrift.TMaintainPeerReq;
import org.apache.iotdb.mpp.rpc.thrift.TRegionLeaderChangeReq;
import org.apache.iotdb.mpp.rpc.thrift.TRegionRouteReq;
import org.apache.iotdb.mpp.rpc.thrift.TRollbackSchemaBlackListReq;
import org.apache.iotdb.mpp.rpc.thrift.TSchemaFetchRequest;
import org.apache.iotdb.mpp.rpc.thrift.TSchemaFetchResponse;
import org.apache.iotdb.mpp.rpc.thrift.TSendFragmentInstanceReq;
import org.apache.iotdb.mpp.rpc.thrift.TSendFragmentInstanceResp;
import org.apache.iotdb.mpp.rpc.thrift.TSendPlanNodeReq;
import org.apache.iotdb.mpp.rpc.thrift.TSendPlanNodeResp;
import org.apache.iotdb.mpp.rpc.thrift.TUpdateConfigNodeGroupReq;
import org.apache.iotdb.mpp.rpc.thrift.TUpdateTemplateReq;
import org.apache.iotdb.mpp.rpc.thrift.TactiveTriggerInstanceReq;
import org.apache.iotdb.mpp.rpc.thrift.TcreateTriggerInstanceReq;
import org.apache.iotdb.rpc.RpcUtils;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.tsfile.exception.NotImplementedException;

import com.google.common.collect.ImmutableList;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class DataNodeInternalRPCServiceImpl implements IDataNodeRPCService.Iface {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(DataNodeInternalRPCServiceImpl.class);
  private final SchemaEngine schemaEngine = SchemaEngine.getInstance();
  private final StorageEngineV2 storageEngine = StorageEngineV2.getInstance();

  private final DataNodeRegionManager regionManager;

  public DataNodeInternalRPCServiceImpl() {
    super();
    regionManager = new DataNodeRegionManager(schemaEngine, storageEngine);
  }

  @Override
  public TSendFragmentInstanceResp sendFragmentInstance(TSendFragmentInstanceReq req) {
    LOGGER.info("receive FragmentInstance to group[{}]", req.getConsensusGroupId());

    // deserialize ConsensusGroupId
    ConsensusGroupId groupId;
    try {
      groupId = ConsensusGroupId.Factory.createFromTConsensusGroupId(req.getConsensusGroupId());
    } catch (Throwable t) {
      LOGGER.error("Deserialize ConsensusGroupId failed. ", t);
      TSendFragmentInstanceResp resp = new TSendFragmentInstanceResp(false);
      resp.setMessage("Deserialize ConsensusGroupId failed: " + t.getMessage());
      return resp;
    }

    // We deserialize here instead of the underlying state machine because parallelism is possible
    // here but not at the underlying state machine
    FragmentInstance fragmentInstance;
    try {
      fragmentInstance = FragmentInstance.deserializeFrom(req.fragmentInstance.body);
    } catch (Throwable t) {
      LOGGER.error("Deserialize FragmentInstance failed.", t);
      TSendFragmentInstanceResp resp = new TSendFragmentInstanceResp(false);
      resp.setMessage("Deserialize FragmentInstance failed: " + t.getMessage());
      return resp;
    }

    // execute fragment instance in state machine
    ConsensusReadResponse readResponse;
    try (SetThreadName threadName = new SetThreadName(fragmentInstance.getId().getFullId())) {
      if (groupId instanceof DataRegionId) {
        readResponse = DataRegionConsensusImpl.getInstance().read(groupId, fragmentInstance);
      } else {
        readResponse = SchemaRegionConsensusImpl.getInstance().read(groupId, fragmentInstance);
      }
      TSendFragmentInstanceResp resp = new TSendFragmentInstanceResp();
      if (!readResponse.isSuccess()) {
        LOGGER.error(
            "Execute FragmentInstance in ConsensusGroup {} failed.",
            req.getConsensusGroupId(),
            readResponse.getException());
        resp.setAccepted(false);
        resp.setMessage(
            "Execute FragmentInstance failed: "
                + (readResponse.getException() == null
                    ? ""
                    : readResponse.getException().getMessage()));
      } else {
        FragmentInstanceInfo info = (FragmentInstanceInfo) readResponse.getDataset();
        resp.setAccepted(!info.getState().isFailed());
        resp.setMessage(info.getMessage());
      }
      return resp;
    } catch (Throwable t) {
      LOGGER.error(
          "Execute FragmentInstance in ConsensusGroup {} failed.", req.getConsensusGroupId(), t);
      TSendFragmentInstanceResp resp = new TSendFragmentInstanceResp(false);
      resp.setMessage("Execute FragmentInstance failed: " + t.getMessage());
      return resp;
    }
  }

  @Override
  public TSendPlanNodeResp sendPlanNode(TSendPlanNodeReq req) {
    LOGGER.info("receive PlanNode to group[{}]", req.getConsensusGroupId());
    ConsensusGroupId groupId =
        ConsensusGroupId.Factory.createFromTConsensusGroupId(req.getConsensusGroupId());
    PlanNode planNode = PlanNodeType.deserialize(req.planNode.body);
    return regionManager.executePlanNode(groupId, planNode);
  }

  @Override
  public TFragmentInstanceStateResp fetchFragmentInstanceState(TFetchFragmentInstanceStateReq req) {
    FragmentInstanceId instanceId = FragmentInstanceId.fromThrift(req.fragmentInstanceId);
    FragmentInstanceInfo info = FragmentInstanceManager.getInstance().getInstanceInfo(instanceId);
    if (info != null) {
      TFragmentInstanceStateResp resp = new TFragmentInstanceStateResp(info.getState().toString());
      resp.setFailedMessages(ImmutableList.of(info.getMessage()));
      return resp;
    } else {
      return new TFragmentInstanceStateResp(FragmentInstanceState.NO_SUCH_INSTANCE.toString());
    }
  }

  @Override
  public TCancelResp cancelQuery(TCancelQueryReq req) {
    try (SetThreadName threadName = new SetThreadName(req.getQueryId())) {
      List<FragmentInstanceId> taskIds =
          req.getFragmentInstanceIds().stream()
              .map(FragmentInstanceId::fromThrift)
              .collect(Collectors.toList());
      for (FragmentInstanceId taskId : taskIds) {
        FragmentInstanceManager.getInstance().cancelTask(taskId);
      }
      return new TCancelResp(true);
    }
  }

  @Override
  public TCancelResp cancelPlanFragment(TCancelPlanFragmentReq req) {
    throw new NotImplementedException();
  }

  @Override
  public TCancelResp cancelFragmentInstance(TCancelFragmentInstanceReq req) {
    throw new NotImplementedException();
  }

  @Override
  public TSchemaFetchResponse fetchSchema(TSchemaFetchRequest req) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TSStatus createSchemaRegion(TCreateSchemaRegionReq req) {
    return regionManager.createSchemaRegion(req.getRegionReplicaSet(), req.getStorageGroup());
  }

  @Override
  public TSStatus createDataRegion(TCreateDataRegionReq req) {
    return regionManager.createDataRegion(
        req.getRegionReplicaSet(), req.getStorageGroup(), req.getTtl());
  }

  @Override
  public TSStatus invalidatePartitionCache(TInvalidateCacheReq req) {
    ClusterPartitionFetcher.getInstance().invalidAllCache();
    return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
  }

  @Override
  public TSStatus invalidateSchemaCache(TInvalidateCacheReq req) {
    DataNodeSchemaCache.getInstance().cleanUp();
    return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
  }

  @Override
  public TSStatus constructSchemaBlackList(TConstructSchemaBlackListReq req) throws TException {
    PathPatternTree patternTree =
        PathPatternTree.deserialize(ByteBuffer.wrap(req.getPathPatternTree()));
    List<TSStatus> failureList = new ArrayList<>();
    TSStatus status;
    int preDeletedNum = 0;
    for (TConsensusGroupId consensusGroupId : req.getSchemaRegionIdList()) {
      status =
          regionManager.executeSchemaPlanNode(
              new SchemaRegionId(consensusGroupId.getId()),
              new ConstructSchemaBlackListNode(new PlanNodeId(""), patternTree));
      if (status.code == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        preDeletedNum += Integer.parseInt(status.getMessage());
      } else {
        failureList.add(status);
      }
    }

    if (!failureList.isEmpty()) {
      return RpcUtils.getStatus(failureList);
    }

    return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS, String.valueOf(preDeletedNum));
  }

  @Override
  public TSStatus rollbackSchemaBlackList(TRollbackSchemaBlackListReq req) throws TException {
    PathPatternTree patternTree =
        PathPatternTree.deserialize(ByteBuffer.wrap(req.getPathPatternTree()));
    List<TSStatus> failureList = new ArrayList<>();
    TSStatus status;
    for (TConsensusGroupId consensusGroupId : req.getSchemaRegionIdList()) {
      status =
          regionManager.executeSchemaPlanNode(
              new SchemaRegionId(consensusGroupId.getId()),
              new RollbackSchemaBlackListNode(new PlanNodeId(""), patternTree));
      if (status.code != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        failureList.add(status);
      }
    }

    if (!failureList.isEmpty()) {
      return RpcUtils.getStatus(failureList);
    }

    return RpcUtils.SUCCESS_STATUS;
  }

  @Override
  public TSStatus invalidateMatchedSchemaCache(TInvalidateMatchedSchemaCacheReq req)
      throws TException {
    DataNodeSchemaCache cache = DataNodeSchemaCache.getInstance();
    cache.takeWriteLock();
    try {
      for (PartialPath pathPattern :
          PathPatternTree.deserialize(ByteBuffer.wrap(req.getPathPatternTree()))
              .getAllPathPatterns()) {
        cache.invalidateMatchedSchema(pathPattern);
      }
    } finally {
      cache.releaseWriteLock();
    }
    return RpcUtils.SUCCESS_STATUS;
  }

  @Override
  public TFetchSchemaBlackListResp fetchSchemaBlackList(TFetchSchemaBlackListReq req)
      throws TException {
    PathPatternTree patternTree = PathPatternTree.deserialize(req.pathPatternTree);
    TFetchSchemaBlackListResp resp = new TFetchSchemaBlackListResp();
    PathPatternTree result = new PathPatternTree();
    for (TConsensusGroupId consensusGroupId : req.getSchemaRegionIdList()) {
      // todo implement as consensus layer read request
      try {
        for (PartialPath path :
            schemaEngine
                .getSchemaRegion(new SchemaRegionId(consensusGroupId.getId()))
                .fetchSchemaBlackList(patternTree)) {
          result.appendFullPath(path);
        }
      } catch (MetadataException e) {
        LOGGER.error(e.getMessage(), e);
        resp.setStatus(RpcUtils.getStatus(e.getErrorCode(), e.getMessage()));
        return resp;
      }
    }
    resp.setStatus(RpcUtils.SUCCESS_STATUS);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
    result.constructTree();
    try {
      result.serialize(dataOutputStream);
    } catch (IOException ignored) {
      // won't reach here
    }
    resp.setPathPatternTree(outputStream.toByteArray());
    return resp;
  }

  @Override
  public TSStatus deleteDataForDeleteTimeSeries(TDeleteDataForDeleteTimeSeriesReq req)
      throws TException {
    PathPatternTree patternTree =
        PathPatternTree.deserialize(ByteBuffer.wrap(req.getPathPatternTree()));
    List<PartialPath> pathList = patternTree.getAllPathPatterns();
    List<TSStatus> failureList = new ArrayList<>();
    TSStatus status;
    for (TConsensusGroupId consensusGroupId : req.getDataRegionIdList()) {
      status =
          regionManager.executeDeleteDataForDeleteTimeSeries(
              new DataRegionId(consensusGroupId.getId()),
              new DeleteDataNode(new PlanNodeId(""), pathList, Long.MIN_VALUE, Long.MAX_VALUE));
      if (status.code != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        failureList.add(status);
      }
    }

    if (!failureList.isEmpty()) {
      return RpcUtils.getStatus(failureList);
    }

    return RpcUtils.SUCCESS_STATUS;
  }

  @Override
  public TSStatus deleteTimeSeries(TDeleteTimeSeriesReq req) throws TException {
    PathPatternTree patternTree =
        PathPatternTree.deserialize(ByteBuffer.wrap(req.getPathPatternTree()));
    List<TSStatus> failureList = new ArrayList<>();
    TSStatus status;
    for (TConsensusGroupId consensusGroupId : req.getSchemaRegionIdList()) {
      status =
          regionManager.executeSchemaPlanNode(
              new SchemaRegionId(consensusGroupId.getId()),
              new DeleteTimeSeriesNode(new PlanNodeId(""), patternTree));
      if (status.code != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        failureList.add(status);
      }
    }

    if (!failureList.isEmpty()) {
      return RpcUtils.getStatus(failureList);
    }

    return RpcUtils.SUCCESS_STATUS;
  }

  @Override
  public THeartbeatResp getDataNodeHeartBeat(THeartbeatReq req) throws TException {
    THeartbeatResp resp = new THeartbeatResp();
    resp.setHeartbeatTimestamp(req.getHeartbeatTimestamp());
    resp.setStatus(CommonDescriptor.getInstance().getConfig().getNodeStatus().getStatus());

    // Judging leader if necessary
    if (req.isNeedJudgeLeader()) {
      resp.setJudgedLeaders(getJudgedLeaders());
    }

    // Sampling load if necessary
    if (MetricConfigDescriptor.getInstance().getMetricConfig().getEnableMetric()
        && req.isNeedSamplingLoad()) {
      long cpuLoad =
          MetricService.getInstance()
              .getOrCreateGauge(
                  Metric.SYS_CPU_LOAD.toString(), MetricLevel.CORE, Tag.NAME.toString(), "system")
              .value();
      if (cpuLoad != 0) {
        resp.setCpu((short) cpuLoad);
      }
      long usedMemory = getMemory("jvm.memory.used.bytes");
      long maxMemory = getMemory("jvm.memory.max.bytes");
      if (usedMemory != 0 && maxMemory != 0) {
        resp.setMemory((short) (usedMemory * 100 / maxMemory));
      }
    }
    return resp;
  }

  @Override
  public TSStatus updateRegionCache(TRegionRouteReq req) throws TException {
    boolean result = ClusterPartitionFetcher.getInstance().updateRegionCache(req);
    if (result) {
      return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
    } else {
      return RpcUtils.getStatus(TSStatusCode.CACHE_UPDATE_FAIL);
    }
  }

  private Map<TConsensusGroupId, Boolean> getJudgedLeaders() {
    Map<TConsensusGroupId, Boolean> result = new HashMap<>();
    if (DataRegionConsensusImpl.getInstance() != null) {
      DataRegionConsensusImpl.getInstance()
          .getAllConsensusGroupIds()
          .forEach(
              groupId -> {
                result.put(
                    groupId.convertToTConsensusGroupId(),
                    DataRegionConsensusImpl.getInstance().isLeader(groupId));
              });
    }

    if (SchemaRegionConsensusImpl.getInstance() != null) {
      SchemaRegionConsensusImpl.getInstance()
          .getAllConsensusGroupIds()
          .forEach(
              groupId -> {
                result.put(
                    groupId.convertToTConsensusGroupId(),
                    SchemaRegionConsensusImpl.getInstance().isLeader(groupId));
              });
    }
    return result;
  }

  private long getMemory(String gaugeName) {
    long result = 0;
    try {
      //
      List<String> heapIds = Arrays.asList("PS Eden Space", "PS Old Eden", "Ps Survivor Space");
      List<String> noHeapIds = Arrays.asList("Code Cache", "Compressed Class Space", "Metaspace");

      for (String id : heapIds) {
        Gauge gauge =
            MetricService.getInstance()
                .getOrCreateGauge(gaugeName, MetricLevel.IMPORTANT, "id", id, "area", "heap");
        result += gauge.value();
      }
      for (String id : noHeapIds) {
        Gauge gauge =
            MetricService.getInstance()
                .getOrCreateGauge(gaugeName, MetricLevel.IMPORTANT, "id", id, "area", "noheap");
        result += gauge.value();
      }
    } catch (Exception e) {
      LOGGER.error("Failed to get memory from metric because {}", e.getMessage());
      return 0;
    }
    return result;
  }

  @Override
  public TSStatus invalidatePermissionCache(TInvalidatePermissionCacheReq req) {
    if (AuthorizerManager.getInstance().invalidateCache(req.getUsername(), req.getRoleName())) {
      return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
    }
    return RpcUtils.getStatus(TSStatusCode.INVALIDATE_PERMISSION_CACHE_ERROR);
  }

  @Override
  public TSStatus merge() throws TException {
    try {
      storageEngine.mergeAll();
    } catch (StorageEngineException e) {
      return RpcUtils.getStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR, e.getMessage());
    }
    return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
  }

  @Override
  public TSStatus flush(TFlushReq req) throws TException {
    return storageEngine.operateFlush(req);
  }

  @Override
  public TSStatus clearCache() throws TException {
    ChunkCache.getInstance().clear();
    TimeSeriesMetadataCache.getInstance().clear();
    BloomFilterCache.getInstance().clear();
    return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
  }

  @Override
  public TSStatus loadConfiguration() throws TException {
    try {
      IoTDBDescriptor.getInstance().loadHotModifiedProps();
    } catch (QueryProcessException e) {
      return RpcUtils.getStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR, e.getMessage());
    }
    return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
  }

  @Override
  public TSStatus setSystemStatus(String status) throws TException {
    try {
      CommonDescriptor.getInstance().getConfig().setNodeStatus(NodeStatus.parse(status));
    } catch (Exception e) {
      return RpcUtils.getStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR, e.getMessage());
    }
    return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
  }

  @Override
  public TSStatus setTTL(TSetTTLReq req) throws TException {
    return storageEngine.setTTL(req);
  }

  @Override
  public TSStatus updateConfigNodeGroup(TUpdateConfigNodeGroupReq req) {
    List<TConfigNodeLocation> configNodeLocations = req.getConfigNodeLocations();
    if (configNodeLocations != null) {
      ConfigNodeInfo.getInstance()
          .updateConfigNodeList(
              configNodeLocations
                  .parallelStream()
                  .map(TConfigNodeLocation::getInternalEndPoint)
                  .collect(Collectors.toList()));
    }
    return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
  }

  @Override
  public TSStatus updateTemplate(TUpdateTemplateReq req) throws TException {
    switch (TemplateInternalRPCUpdateType.getType(req.type)) {
      case ADD_TEMPLATE_SET_INFO:
        ClusterTemplateManager.getInstance().updateTemplateSetInfo(req.getTemplateInfo());
        break;
      case INVALIDATE_TEMPLATE_SET_INFO:
        ClusterTemplateManager.getInstance().invalidateTemplateSetInfo(req.getTemplateInfo());
        break;
    }
    return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
  }

  @Override
  public TSStatus deleteRegion(TConsensusGroupId tconsensusGroupId) {
    ConsensusGroupId consensusGroupId =
        ConsensusGroupId.Factory.createFromTConsensusGroupId(tconsensusGroupId);
    if (consensusGroupId instanceof DataRegionId) {
      ConsensusGenericResponse response =
          DataRegionConsensusImpl.getInstance().deletePeer(consensusGroupId);
      if (!response.isSuccess()
          && !(response.getException() instanceof PeerNotInConsensusGroupException)) {
        return RpcUtils.getStatus(
            TSStatusCode.DELETE_REGION_ERROR, response.getException().getMessage());
      }
      return regionManager.deleteDataRegion((DataRegionId) consensusGroupId);
    } else {
      ConsensusGenericResponse response =
          SchemaRegionConsensusImpl.getInstance().deletePeer(consensusGroupId);
      if (!response.isSuccess()
          && !(response.getException() instanceof PeerNotInConsensusGroupException)) {
        return RpcUtils.getStatus(
            TSStatusCode.DELETE_REGION_ERROR, response.getException().getMessage());
      }
      return regionManager.deleteSchemaRegion((SchemaRegionId) consensusGroupId);
    }
  }

  public TSStatus changeRegionLeader(TRegionLeaderChangeReq req) throws TException {
    TSStatus status = new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
    TConsensusGroupId tgId = req.getRegionId();
    ConsensusGroupId regionId = ConsensusGroupId.Factory.createFromTConsensusGroupId(tgId);
    TEndPoint newNode = getConsensusEndPoint(req.getNewLeaderNode(), regionId);
    Peer newLeaderPeer = new Peer(regionId, newNode);
    if (!isLeader(regionId)) {
      LOGGER.info("region {} is not leader, no need to change leader", regionId);
      return status;
    }
    LOGGER.info("region {} is leader, will change leader", regionId);
    return transferLeader(regionId, newLeaderPeer);
  }

  private TSStatus transferLeader(ConsensusGroupId regionId, Peer newLeaderPeer) {
    TSStatus status = new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
    ConsensusGenericResponse resp;
    if (regionId instanceof DataRegionId) {
      resp = DataRegionConsensusImpl.getInstance().transferLeader(regionId, newLeaderPeer);
    } else if (regionId instanceof SchemaRegionId) {
      resp = SchemaRegionConsensusImpl.getInstance().transferLeader(regionId, newLeaderPeer);
    } else {
      status.setCode(TSStatusCode.REGION_LEADER_CHANGE_FAILED.getStatusCode());
      status.setMessage("Error Region type. region: " + regionId);
      return status;
    }
    if (!resp.isSuccess()) {
      LOGGER.error("change region {} leader failed", regionId, resp.getException());
      status.setCode(TSStatusCode.REGION_LEADER_CHANGE_FAILED.getStatusCode());
      status.setMessage(resp.getException().getMessage());
      return status;
    }
    status.setMessage("change region " + regionId + " leader succeed");
    return status;
  }

  private boolean isLeader(ConsensusGroupId regionId) {
    if (regionId instanceof DataRegionId) {
      return DataRegionConsensusImpl.getInstance().isLeader(regionId);
    }
    if (regionId instanceof SchemaRegionId) {
      return SchemaRegionConsensusImpl.getInstance().isLeader(regionId);
    }
    LOGGER.error("region {} type is illegal", regionId);
    return false;
  }

  @Override
  public TSStatus createNewRegionPeer(TCreatePeerReq req) throws TException {
    ConsensusGroupId regionId =
        ConsensusGroupId.Factory.createFromTConsensusGroupId(req.getRegionId());
    List<Peer> peers =
        req.getRegionLocations().stream()
            .map(location -> new Peer(regionId, getConsensusEndPoint(location, regionId)))
            .collect(Collectors.toList());
    TSStatus status = createNewRegion(regionId, req.getStorageGroup(), req.getTtl());
    if (!isSucceed(status)) {
      return status;
    }
    return createNewRegionPeer(regionId, peers);
  }

  @Override
  public TSStatus addRegionPeer(TMaintainPeerReq req) throws TException {
    TConsensusGroupId regionId = req.getRegionId();
    String selectedDataNodeIP = req.getDestNode().getInternalEndPoint().getIp();
    boolean submitSucceed = RegionMigrateService.getInstance().submitAddRegionPeerTask(req);
    TSStatus status = new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
    if (submitSucceed) {
      LOGGER.info(
          "Successfully submit addRegionPeer task for region: {} on DataNode: {}",
          regionId,
          selectedDataNodeIP);
      return status;
    }
    status.setCode(TSStatusCode.MIGRATE_REGION_ERROR.getStatusCode());
    status.setMessage("Submit addRegionPeer task failed, region: " + regionId);
    return status;
  }

  @Override
  public TSStatus removeRegionPeer(TMaintainPeerReq req) throws TException {
    TConsensusGroupId regionId = req.getRegionId();
    String selectedDataNodeIP = req.getDestNode().getInternalEndPoint().getIp();
    boolean submitSucceed = RegionMigrateService.getInstance().submitRemoveRegionPeerTask(req);
    TSStatus status = new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
    if (submitSucceed) {
      LOGGER.info(
          "Successfully submit removeRegionPeer task for region: {} on DataNode: {}",
          regionId,
          selectedDataNodeIP);
      return status;
    }
    status.setCode(TSStatusCode.MIGRATE_REGION_ERROR.getStatusCode());
    status.setMessage("Submit removeRegionPeer task failed, region: " + regionId);
    return status;
  }

  @Override
  public TSStatus deleteOldRegionPeer(TMaintainPeerReq req) throws TException {
    TConsensusGroupId regionId = req.getRegionId();
    String selectedDataNodeIP = req.getDestNode().getInternalEndPoint().getIp();
    boolean submitSucceed = RegionMigrateService.getInstance().submitDeleteOldRegionPeerTask(req);
    TSStatus status = new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
    if (submitSucceed) {
      LOGGER.info(
          "Successfully submit deleteOldRegionPeer task for region: {} on DataNode: {}",
          regionId,
          selectedDataNodeIP);
      return status;
    }
    status.setCode(TSStatusCode.MIGRATE_REGION_ERROR.getStatusCode());
    status.setMessage("Submit deleteOldRegionPeer task failed, region: " + regionId);
    return status;
  }

  private TSStatus createNewRegion(ConsensusGroupId regionId, String storageGroup, long ttl) {
    return regionManager.createNewRegion(regionId, storageGroup, ttl);
  }

  @Override
  public TSStatus createFunction(TCreateFunctionRequest request) {
    try {
      UDFRegistrationService.getInstance()
          .register(
              request.getUdfName(),
              request.getClassName(),
              request.getUris(),
              UDFExecutableManager.getInstance(),
              true);
      return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
    } catch (Exception e) {
      return new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode())
          .setMessage(e.getMessage());
    }
  }

  @Override
  public TSStatus dropFunction(TDropFunctionRequest request) {
    try {
      UDFRegistrationService.getInstance().deregister(request.getUdfName());
      return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
    } catch (Exception e) {
      return new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode())
          .setMessage(e.getMessage());
    }
  }

  @Override
  public TSStatus createTriggerInstance(TcreateTriggerInstanceReq req) throws TException {
    TriggerInformation triggerInformation = TriggerInformation.deserialize(req.triggerInformation);
    try {
      // set state to INACTIVE when creating trigger instance
      triggerInformation.setTriggerState(TTriggerState.INACTIVE);
      // save jar file at trigger_lib_dir
      TriggerExecutableManager.getInstance()
          .writeToLibDir(req.jarFile, triggerInformation.getJarName());
      // register trigger information with TriggerRegistrationService
      // config nodes take responsibility for synchronization control
      TriggerManagementService.getInstance().register(triggerInformation);
    } catch (Exception e) {
      LOGGER.warn(
          "Error occurred when creating trigger instance for trigger: {}. The cause is {}.",
          triggerInformation.getTriggerName(),
          e.getMessage());
      return new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode())
          .setMessage(e.getMessage());
    }
    return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
  }

  @Override
  public TSStatus activeTriggerInstance(TactiveTriggerInstanceReq req) throws TException {
    try {
      TriggerManagementService.getInstance().activeTrigger(req.triggerName);
    } catch (Exception e) {
      LOGGER.error("Error occurred during ");
      return new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode())
          .setMessage(e.getMessage());
    }

    return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
  }

  @Override
  public TSStatus dropTriggerInstance(TDropTriggerInstanceReq req) throws TException {
    // todo: implementation
    return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
  }

  private TEndPoint getConsensusEndPoint(
      TDataNodeLocation nodeLocation, ConsensusGroupId regionId) {
    if (regionId instanceof DataRegionId) {
      return nodeLocation.getDataRegionConsensusEndPoint();
    }
    return nodeLocation.getSchemaRegionConsensusEndPoint();
  }

  private boolean isSucceed(TSStatus status) {
    return status.getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode();
  }

  private TSStatus createNewRegionPeer(ConsensusGroupId regionId, List<Peer> peers) {
    LOGGER.info("Start to createNewRegionPeer {} to region {}", peers, regionId);
    TSStatus status = new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
    ConsensusGenericResponse resp;
    if (regionId instanceof DataRegionId) {
      resp = DataRegionConsensusImpl.getInstance().createPeer(regionId, peers);
    } else {
      resp = SchemaRegionConsensusImpl.getInstance().createPeer(regionId, peers);
    }
    if (!resp.isSuccess()) {
      LOGGER.error(
          "CreateNewRegionPeer error, peers: {}, regionId: {}, errorMessage",
          peers,
          regionId,
          resp.getException());
      status.setCode(TSStatusCode.REGION_MIGRATE_FAILED.getStatusCode());
      status.setMessage(resp.getException().getMessage());
      return status;
    }
    LOGGER.info("Succeed to createNewRegionPeer {} for region {}", peers, regionId);
    status.setMessage("createNewRegionPeer succeed, regionId: " + regionId);
    return status;
  }

  @Override
  public TSStatus disableDataNode(TDisableDataNodeReq req) throws TException {
    LOGGER.info("start disable data node in the request: {}", req);
    TSStatus status = new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
    status.setMessage("disable datanode succeed");
    // TODO what need to clean?
    ClusterPartitionFetcher.getInstance().invalidAllCache();
    DataNodeSchemaCache.getInstance().cleanUp();
    return status;
  }

  @Override
  public TSStatus stopDataNode() {
    TSStatus status = new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
    LOGGER.info("Execute stopDataNode RPC method");

    // kill the datanode process 20 seconds later
    // because datanode process cannot exit normally for the reason of InterruptedException
    new Thread(
            () -> {
              try {
                TimeUnit.SECONDS.sleep(20);
              } catch (InterruptedException e) {
                LOGGER.error("Meets InterruptedException in stopDataNode RPC method");
              } finally {
                LOGGER.info("Executing system.exit(0) in stopDataNode RPC method after 20 seconds");
                System.exit(0);
              }
            })
        .start();

    try {
      DataNode.getInstance().stop();
      status.setMessage("stop datanode succeed");
    } catch (Exception e) {
      LOGGER.error("stop Data Node error", e);
      status.setCode(TSStatusCode.DATANODE_STOP_ERROR.getStatusCode());
      status.setMessage(e.getMessage());
    }
    return status;
  }

  public void handleClientExit() {}
}
