// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.recordservice.mr;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.recordservice.core.NetworkAddress;
import com.cloudera.recordservice.core.PlanRequestResult;
import com.cloudera.recordservice.core.RecordServiceException;
import com.cloudera.recordservice.core.RecordServicePlannerClient;
import com.cloudera.recordservice.core.Request;
import com.cloudera.recordservice.core.Task;
import com.cloudera.recordservice.mapreduce.RecordServiceInputSplit;
import com.cloudera.recordservice.mr.security.DelegationTokenIdentifier;
import com.cloudera.recordservice.mr.security.TokenUtils;
import com.google.common.base.Preconditions;

/**
 * Utilities to communicate with the planner.
 */
public class PlanUtil {
  private final static Logger LOG = LoggerFactory.getLogger(PlanUtil.class);

  // Encapsulates results of a plan request, returning the splits and the schema.
  public static class SplitsInfo {
    public final List<InputSplit> splits;
    public final Schema schema;

    public SplitsInfo(List<InputSplit> splits, Schema schema) {
      this.splits = splits;
      this.schema = schema;
    }
  }

  /**
   * Generates a request from the configs set in jobConf.
   */
  public static Request getRequest(Configuration jobConf) throws IOException {
    LOG.debug("Generating input splits.");

    String tblName = jobConf.get(RecordServiceConfig.TBL_NAME_CONF);
    String inputDir = jobConf.get(FileInputFormat.INPUT_DIR);
    String sqlQuery = jobConf.get(RecordServiceConfig.QUERY_NAME_CONF);

    int numSet = 0;
    if (tblName != null) ++numSet;
    if (inputDir != null) ++numSet;
    if (sqlQuery != null) ++numSet;

    if (numSet == 0) {
      throw new IllegalArgumentException("No input specified. Specify either '" +
          RecordServiceConfig.TBL_NAME_CONF + "', '" +
          RecordServiceConfig.QUERY_NAME_CONF + "' or '" +
          FileInputFormat.INPUT_DIR + "'");
    }
    if (numSet > 1) {
      throw new IllegalArgumentException("More than one input specified. Can " +
          "only specify one of '" +
          RecordServiceConfig.TBL_NAME_CONF + "'=" + tblName + ", '" +
          FileInputFormat.INPUT_DIR + "'=" + inputDir + ", '" +
          RecordServiceConfig.QUERY_NAME_CONF + "'=" + sqlQuery);
    }

    String[] colNames = jobConf.getStrings(RecordServiceConfig.COL_NAMES_CONF);
    if (colNames == null) colNames = new String[0];

    if (tblName == null && colNames.length > 0) {
      // TODO: support this.
      throw new IllegalArgumentException(
          "Column projections can only be specified with table inputs.");
    }

    Request request = null;
    if (tblName != null) {
      if (colNames.length == 0) {
        // If length of colNames = 0, return all possible columns
        // TODO: this has slightly different meaning than createProjectionRequest()
        // which treats empty columns as an empty projection. i.e. select * vs count(*)
        // Reconcile this.
        request = Request.createTableScanRequest(tblName);
      } else {
        List<String> projection = new ArrayList<String>();
        for (String c: colNames) {
          if (c == null || c.isEmpty()) {
            throw new IllegalArgumentException(
                "Cannot specify projection with null or empty column name.");
          }
          projection.add(c);
        }
        request = Request.createProjectionRequest(tblName, projection);
      }
    } else if (inputDir != null) {
      // TODO: inputDir is a comma separate list of paths. The service needs to
      // handle that.
      if (inputDir.contains(",")) {
        throw new IllegalArgumentException(
            "Only reading a single directory is currently supported.");
      }
      request = Request.createPathRequest(inputDir);
    } else if (sqlQuery != null) {
      request = Request.createSqlRequest(sqlQuery);
    } else {
      Preconditions.checkState(false);
    }
    return request;
  }

  /**
   * Returns a connected planner client from the jobConf. The caller needs to close
   * the planner.
   */
  @SuppressWarnings("unchecked")
  public static RecordServicePlannerClient getPlanner(
      Configuration jobConf,
      RecordServicePlannerClient.Builder builder,
      List<NetworkAddress> plannerHostPorts,
      String kerberosPrincipal,
      Credentials credentials) throws IOException {

    // If debug mode is enabled, dump all the configuration properties and their
    // sources to the log.
    if (LOG.isDebugEnabled()) {
      LOG.debug(dumpConfiguration(jobConf, LOG.isTraceEnabled()));
    }

    // Try to get the delegation token from the credentials. If it is there, use it.
    Token<DelegationTokenIdentifier> delegationToken = null;
    if (credentials != null) {
      delegationToken = (Token<DelegationTokenIdentifier>) credentials.getToken(
            DelegationTokenIdentifier.DELEGATION_KIND);
    }

    if (delegationToken != null) {
      builder.setDelegationToken(TokenUtils.toDelegationToken(delegationToken));
    } else if (kerberosPrincipal != null) {
      builder.setKerberosPrincipal(kerberosPrincipal);
    }

    // Try all the host ports in order.
    // TODO: we can randomize the list for load balancing but it might be more
    // efficient to be sticky (hotter cache, etc).
    RecordServicePlannerClient planner = null;
    Exception lastException = null;
    for (int i = 0; i < plannerHostPorts.size(); ++i) {
      NetworkAddress hostPort = plannerHostPorts.get(i);
      try {
        planner = builder.connect(hostPort.hostname, hostPort.port);
        if (planner != null) return planner;
      } catch (RecordServiceException e) {
        // Ignore, try next host. The errors in builder should be sufficient.
        lastException = e;
      } catch (IOException e) {
        // Ignore, try next host. The errors in builder should be sufficient.
        lastException = e;
      }
    }
    throw new IOException(
        "Could not connect to any of the configured planners.", lastException);
  }

  /**
   * This also handles authentication using credentials. If there is a delegation
   * token in the credentials, that will be used to authenticate the planner
   * connection. Otherwise, if kerberos is enabled, a token will be generated
   * and added to the credentials.
   * TODO: is this behavior sufficient? Do we need to fall back and renew tokens
   * or does the higher level framework (i.e. oozie) do that?
   */
  public static SplitsInfo getSplits(Configuration jobConf,
                                     Credentials credentials) throws IOException {
    Request request = PlanUtil.getRequest(jobConf);
    List<NetworkAddress> plannerHostPorts = RecordServiceConfig.getPlannerHostPort(
        jobConf.get(RecordServiceConfig.PLANNER_HOSTPORTS_CONF,
            RecordServiceConfig.DEFAULT_PLANNER_HOSTPORTS));
    String kerberosPrincipal =
        jobConf.get(RecordServiceConfig.KERBEROS_PRINCIPAL_CONF);
    int connectionTimeoutMs =
        jobConf.getInt(RecordServiceConfig.PLANNER_CONNECTION_TIMEOUT_MS_CONF, -1);
    int rpcTimeoutMs =
            jobConf.getInt(RecordServiceConfig.PLANNER_RPC_TIMEOUT_MS_CONF, -1);
    int maxAttempts =
        jobConf.getInt(RecordServiceConfig.PLANNER_RETRY_ATTEMPTS_CONF, -1);
    int sleepDurationMs =
        jobConf.getInt(RecordServiceConfig.PLANNER_RETRY_SLEEP_MS_CONF, -1);
    int maxTasks = jobConf.getInt(RecordServiceConfig.PLANNER_REQUEST_MAX_TASKS, -1);

    RecordServicePlannerClient.Builder builder =
        new RecordServicePlannerClient.Builder();

    if (connectionTimeoutMs != -1) builder.setConnectionTimeoutMs(connectionTimeoutMs);
    if (rpcTimeoutMs != -1) builder.setRpcTimeoutMs(rpcTimeoutMs);
    if (maxAttempts != -1) builder.setMaxAttempts(maxAttempts);
    if (sleepDurationMs != -1) builder.setSleepDurationMs(sleepDurationMs);
    if (maxTasks != -1) builder.setMaxTasks(maxTasks);

    PlanRequestResult result = null;
    RecordServicePlannerClient planner = PlanUtil.getPlanner(
        jobConf, builder, plannerHostPorts, kerberosPrincipal, credentials);

    try {
      result = planner.planRequest(request);
      if (planner.isKerberosAuthenticated()) {
        // We need to get a delegation token and populate credentials (for the map tasks)
        // TODO: what to set as renewer?
        Token<DelegationTokenIdentifier> delegationToken =
            TokenUtils.fromTDelegationToken(planner.getDelegationToken(""));
        credentials.addToken(DelegationTokenIdentifier.DELEGATION_KIND, delegationToken);
      }
    } catch (RecordServiceException e) {
      throw new IOException(e);
    } finally {
      if (planner != null) planner.close();
    }

    Schema schema = new Schema(result.schema);
    List<InputSplit> splits = new ArrayList<InputSplit>();
    for (Task t : result.tasks) {
      splits.add(new RecordServiceInputSplit(schema, new TaskInfo(t, result.hosts)));
    }
    LOG.debug(String.format("Generated %d splits.", splits.size()));

    // Randomize the order of the splits to mitigate skew.
    Collections.shuffle(splits);
    return new SplitsInfo(splits, schema);
  }

  /**
   * Return all configuration properties info (name, value, and source).
   * This is useful for debugging.
   * If `dumpAll` is false, only dump properties that start with 'recordservice'.
   * Otherwise, it dumps all properties in the `conf`.
   */
  public static String dumpConfiguration(Configuration conf, boolean dumpAll) {
    // TODO: how do we handle SparkConf and SQLConf? Seems like they didn't offer
    // facility to track a property to its source.
    StringBuilder sb = new StringBuilder();
    sb.append('\n');
    sb.append("=============== Begin of Configuration Properties Info ===============");
    for (Map.Entry<String, String> e : conf) {
      if (!dumpAll && !e.getKey().startsWith("recordservice")) continue;
      String[] sources = conf.getPropertySources(e.getKey());
      String source;
      if (sources == null || sources.length == 0) {
        source = "Not Found";
      } else {
        // Only get the newest source that this property comes from.
        source = sources[sources.length - 1];
        URL url = conf.getResource(source);
        // If there's a URL with this resource, use that.
        if (url != null) source = url.toString();
      }
      sb.append('\n');
      sb.append(String.format(
          "Property Name: %s\tValue: %s\tSource: %s",
          e.getKey(), e.getValue(), source));
    }
    sb.append('\n');
    sb.append("================ End of Configuration Properties Info ================");
    return sb.toString();
  }
}
