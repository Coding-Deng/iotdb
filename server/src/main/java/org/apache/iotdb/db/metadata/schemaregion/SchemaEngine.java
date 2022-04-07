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

package org.apache.iotdb.db.metadata.schemaregion;

import org.apache.iotdb.commons.consensus.ConsensusGroupId;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.metadata.SchemaEngineType;
import org.apache.iotdb.db.metadata.mnode.IStorageGroupMNode;
import org.apache.iotdb.db.metadata.path.PartialPath;
import org.apache.iotdb.db.metadata.schemaregion.rocksdb.RSchemaRegion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// manage all the schemaRegion in this dataNode
public class SchemaEngine {

  private Map<ConsensusGroupId, ISchemaRegion> schemaRegionMap;
  private SchemaEngineType schemaRegionStoredType;
  private static final Logger logger = LoggerFactory.getLogger(SchemaEngine.class);

  private static class SchemaEngineManagerHolder {
    private static final SchemaEngine INSTANCE = new SchemaEngine();

    private SchemaEngineManagerHolder() {}
  }

  private SchemaEngine() {}

  public static SchemaEngine getInstance() {
    return SchemaEngineManagerHolder.INSTANCE;
  }

  public void init() {
    schemaRegionMap = new ConcurrentHashMap<>();
    schemaRegionStoredType = IoTDBDescriptor.getInstance().getConfig().getMetadataManagerType();
    logger.info("used schema engine type: {}.", schemaRegionStoredType);
  }

  public void clear() {
    if (schemaRegionMap != null) {
      schemaRegionMap.clear();
      schemaRegionMap = null;
    }
  }

  public ISchemaRegion getSchemaRegion(ConsensusGroupId schemaRegionId) {
    return schemaRegionMap.get(schemaRegionId);
  }

  public Collection<ISchemaRegion> getAllSchemaRegions() {
    return schemaRegionMap.values();
  }

  public synchronized ISchemaRegion createSchemaRegion(
      PartialPath storageGroup,
      ConsensusGroupId schemaRegionId,
      IStorageGroupMNode storageGroupMNode)
      throws MetadataException {
    ISchemaRegion schemaRegion = schemaRegionMap.get(schemaRegionId);
    if (schemaRegion != null) {
      return schemaRegion;
    }
    switch (schemaRegionStoredType) {
      case MEMORY_BASED:
        schemaRegion = new SchemaRegion(storageGroup, schemaRegionId, storageGroupMNode);
        break;
      case ROCKSDB_BASED:
        schemaRegion = new RSchemaRegion(storageGroup, schemaRegionId, storageGroupMNode);
        break;
      default:
        throw new UnsupportedOperationException(
            String.format(
                "This type [%s] is not supported. Please check and modify it.",
                schemaRegionStoredType));
    }
    schemaRegionMap.put(schemaRegionId, schemaRegion);
    return schemaRegion;
  }

  public void deleteSchemaRegion(ConsensusGroupId schemaRegionId) throws MetadataException {
    schemaRegionMap.remove(schemaRegionId).deleteSchemaRegion();
  }
}