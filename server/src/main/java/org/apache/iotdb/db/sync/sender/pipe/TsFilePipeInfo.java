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
package org.apache.iotdb.db.sync.sender.pipe;

import java.util.Objects;

public class TsFilePipeInfo extends PipeInfo {
  private boolean syncDelOp;
  private long dataStartTimestamp;

  public TsFilePipeInfo(
      String pipeName,
      String pipeSinkName,
      long createTime,
      long dataStartTimestamp,
      boolean syncDelOp) {
    super(pipeName, pipeSinkName, createTime);
    this.dataStartTimestamp = dataStartTimestamp;
    this.syncDelOp = syncDelOp;
  }

  public TsFilePipeInfo(
      String pipeName,
      String pipeSinkName,
      Pipe.PipeStatus status,
      long createTime,
      long dataStartTimestamp,
      boolean syncDelOp) {
    super(pipeName, pipeSinkName, status, createTime);
    this.dataStartTimestamp = dataStartTimestamp;
    this.syncDelOp = syncDelOp;
  }

  public boolean isSyncDelOp() {
    return syncDelOp;
  }

  public void setSyncDelOp(boolean syncDelOp) {
    this.syncDelOp = syncDelOp;
  }

  public long getDataStartTimestamp() {
    return dataStartTimestamp;
  }

  public void setDataStartTimestamp(long dataStartTimestamp) {
    this.dataStartTimestamp = dataStartTimestamp;
  }

  @Override
  public String toString() {
    return "TsFilePipeInfo{"
        + "pipeName='"
        + pipeName
        + '\''
        + ", pipeSinkName='"
        + pipeSinkName
        + '\''
        + ", status="
        + status
        + ", createTime="
        + createTime
        + ", syncDelOp="
        + syncDelOp
        + ", dataStartTimestamp="
        + dataStartTimestamp
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TsFilePipeInfo that = (TsFilePipeInfo) o;
    return syncDelOp == that.syncDelOp
        && dataStartTimestamp == that.dataStartTimestamp
        && createTime == that.createTime
        && Objects.equals(pipeName, that.pipeName)
        && Objects.equals(pipeSinkName, that.pipeSinkName)
        && status == that.status;
  }

  @Override
  public int hashCode() {
    return Objects.hash(syncDelOp, dataStartTimestamp, pipeName, pipeSinkName, status, createTime);
  }
}
