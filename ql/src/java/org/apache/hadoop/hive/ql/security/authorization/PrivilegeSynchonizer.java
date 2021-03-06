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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.security.authorization;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.HiveObjectPrivilege;
import org.apache.hadoop.hive.metastore.api.HiveObjectRef;
import org.apache.hadoop.hive.metastore.api.HiveObjectType;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.PrivilegeBag;
import org.apache.hadoop.hive.metastore.api.PrivilegeGrantInfo;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAuthorizer;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAuthzPluginException;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveMetastoreClientFactoryImpl;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePolicyProvider;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeObject;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeObject.HivePrivilegeObjectType;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveResourceACLs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PrivilegeSynchonizer defines a thread to synchronize privileges from
 * external authorizer to Hive metastore.
 */
public class PrivilegeSynchonizer implements Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(PrivilegeSynchonizer.class);
  public static final String GRANTOR = "ranger";
  private IMetaStoreClient hiveClient;
  private LeaderLatch privilegeSynchonizerLatch;
  private HiveConf hiveConf;
  private HiveAuthorizer authorizer;

  public PrivilegeSynchonizer(LeaderLatch privilegeSynchonizerLatch, HiveAuthorizer authorizer, HiveConf hiveConf) {
    try {
      hiveClient = new HiveMetastoreClientFactoryImpl().getHiveMetastoreClient();
    } catch (HiveAuthzPluginException e) {
      throw new RuntimeException("Error creating getHiveMetastoreClient", e);
    }
    this.privilegeSynchonizerLatch = privilegeSynchonizerLatch;
    this.authorizer = authorizer;
    this.hiveConf = hiveConf;
  }

  private void addACLsToBag(
      Map<String, Map<HiveResourceACLs.Privilege, HiveResourceACLs.AccessResult>> principalAclsMap,
      PrivilegeBag privBag, HiveObjectType objectType, String dbName, String tblName, String columnName,
      PrincipalType principalType) {

    for (Map.Entry<String, Map<HiveResourceACLs.Privilege, HiveResourceACLs.AccessResult>> principalAcls
        : principalAclsMap.entrySet()) {
      String principal = principalAcls.getKey();
      for (Map.Entry<HiveResourceACLs.Privilege, HiveResourceACLs.AccessResult> acl : principalAcls.getValue()
          .entrySet()) {
        if (acl.getValue() == HiveResourceACLs.AccessResult.ALLOWED) {
          switch (objectType) {
          case DATABASE:
            privBag.addToPrivileges(
                new HiveObjectPrivilege(new HiveObjectRef(HiveObjectType.DATABASE, dbName, null, null, null), principal,
                    principalType, new PrivilegeGrantInfo(acl.getKey().toString(),
                        (int) (System.currentTimeMillis() / 1000), GRANTOR, PrincipalType.USER, false)));
            break;
          case TABLE:
            privBag.addToPrivileges(
                new HiveObjectPrivilege(new HiveObjectRef(HiveObjectType.TABLE, dbName, tblName, null, null), principal,
                    principalType, new PrivilegeGrantInfo(acl.getKey().toString(),
                        (int) (System.currentTimeMillis() / 1000), GRANTOR, PrincipalType.USER, false)));
            break;
          case COLUMN:
            privBag.addToPrivileges(
                new HiveObjectPrivilege(new HiveObjectRef(HiveObjectType.COLUMN, dbName, tblName, null, columnName),
                    principal, principalType, new PrivilegeGrantInfo(acl.getKey().toString(),
                        (int) (System.currentTimeMillis() / 1000), GRANTOR, PrincipalType.USER, false)));
            break;
          default:
            throw new RuntimeException("Get unknown object type " + objectType);
          }
        }
      }
    }
  }

  private HiveObjectRef getObjToRefresh(HiveObjectType type, String dbName, String tblName) throws Exception {
    HiveObjectRef objToRefresh = null;
    switch (type) {
    case DATABASE:
      objToRefresh = new HiveObjectRef(HiveObjectType.DATABASE, dbName, null, null, null);
      break;
    case TABLE:
      objToRefresh = new HiveObjectRef(HiveObjectType.TABLE, dbName, tblName, null, null);
      break;
    case COLUMN:
      objToRefresh = new HiveObjectRef(HiveObjectType.COLUMN, dbName, tblName, null, null);
      break;
    default:
      throw new RuntimeException("Get unknown object type " + type);
    }
    return objToRefresh;
  }

  private void addGrantPrivilegesToBag(HivePolicyProvider policyProvider, PrivilegeBag privBag, HiveObjectType type,
      String dbName, String tblName, String columnName) throws Exception {

    HiveResourceACLs objectAcls = null;

    switch (type) {
    case DATABASE:
      objectAcls = policyProvider
          .getResourceACLs(new HivePrivilegeObject(HivePrivilegeObjectType.DATABASE, dbName, null));
      break;

    case TABLE:
      objectAcls = policyProvider
          .getResourceACLs(new HivePrivilegeObject(HivePrivilegeObjectType.TABLE_OR_VIEW, dbName, tblName));
      break;

    case COLUMN:
      objectAcls = policyProvider
          .getResourceACLs(new HivePrivilegeObject(HivePrivilegeObjectType.COLUMN, dbName, tblName, null, columnName));
      break;

    default:
      throw new RuntimeException("Get unknown object type " + type);
    }

    if (objectAcls == null) {
      return;
    }

    addACLsToBag(objectAcls.getUserPermissions(), privBag, type, dbName, tblName, columnName, PrincipalType.USER);
    addACLsToBag(objectAcls.getGroupPermissions(), privBag, type, dbName, tblName, columnName, PrincipalType.GROUP);
  }

  @Override
  public void run() {
    while (true) {
      try {
        HivePolicyProvider policyProvider = authorizer.getHivePolicyProvider();
        long interval = HiveConf.getTimeVar(hiveConf, ConfVars.HIVE_PRIVILEGE_SYNCHRONIZER_INTERVAL, TimeUnit.SECONDS);
        if (hiveConf.getBoolVar(ConfVars.HIVE_PRIVILEGE_SYNCHRONIZER)) {
          if (!privilegeSynchonizerLatch.await(interval, TimeUnit.SECONDS)) {
            continue;
          }
          LOG.debug("Start synchonize privilege");
          for (String dbName : hiveClient.getAllDatabases()) {
            HiveObjectRef dbToRefresh = getObjToRefresh(HiveObjectType.DATABASE, dbName, null);
            PrivilegeBag grantDatabaseBag = new PrivilegeBag();
            addGrantPrivilegesToBag(policyProvider, grantDatabaseBag, HiveObjectType.DATABASE, dbName, null, null);
            hiveClient.refresh_privileges(dbToRefresh, grantDatabaseBag);

            for (String tblName : hiveClient.getAllTables(dbName)) {
              HiveObjectRef tableToRefresh = getObjToRefresh(HiveObjectType.TABLE, dbName, tblName);
              PrivilegeBag grantTableBag = new PrivilegeBag();
              addGrantPrivilegesToBag(policyProvider, grantTableBag, HiveObjectType.TABLE, dbName, tblName, null);
              hiveClient.refresh_privileges(tableToRefresh, grantTableBag);

              HiveObjectRef tableOfColumnsToRefresh = getObjToRefresh(HiveObjectType.COLUMN, dbName, tblName);
              PrivilegeBag grantColumnBag = new PrivilegeBag();
              Table tbl = hiveClient.getTable(dbName, tblName);
              for (FieldSchema fs : tbl.getPartitionKeys()) {
                addGrantPrivilegesToBag(policyProvider, grantColumnBag, HiveObjectType.COLUMN, dbName, tblName,
                    fs.getName());
              }
              for (FieldSchema fs : tbl.getSd().getCols()) {
                addGrantPrivilegesToBag(policyProvider, grantColumnBag, HiveObjectType.COLUMN, dbName, tblName,
                    fs.getName());
              }
              hiveClient.refresh_privileges(tableOfColumnsToRefresh, grantColumnBag);
            }
          }
        }
        // Wait if no exception happens, otherwise, retry immediately
        Thread.sleep(interval * 1000);
        LOG.debug("Success synchonize privilege");
      } catch (Exception e) {
        LOG.error("Error initializing PrivilegeSynchonizer: " + e.getMessage(), e);
      }
    }
  }
}
