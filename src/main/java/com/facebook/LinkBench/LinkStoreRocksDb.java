/*
 * Copyright 2012, Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.LinkBench;

import com.facebook.rocks.swift.*;
import com.facebook.swift.service.ThriftClientManager;
import com.facebook.nifty.client.FramedClientConnector;
import com.google.common.net.HostAndPort;
import org.apache.thrift.transport.TTransportException;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.commons.codec.binary.Hex;

import static com.google.common.net.HostAndPort.fromParts;

/*
 * This file implements Linkbench methods for loading/requesting data to rocksDb
 * database server by calling thrift apis after creating a java thrift client
 * through swift : rocksClient for the link and the node operations.
 */

public class LinkStoreRocksDb extends GraphStore {
  private static final ThriftClientManager clientManager =
    new ThriftClientManager();
  private ThreadLocal<RocksService> rocksClient =
      new ThreadLocal<RocksService>();

  /* RocksDb database server configuration keys */
  public static final String CONFIG_HOST = "host";
  public static final String CONFIG_PORT = "port";
  public static final String CONFIG_WRITE_SYNC = "write_options_sync";
  public static final String CONFIG_WRITE_DISABLE_WAL =
    "write_options_disableWAL";

  public static final String CONFIG_USER = "user";
  public static final String CONFIG_PASSWORD = "password";

  public static final int DEFAULT_BULKINSERT_SIZE = 1024;
  private static final boolean INTERNAL_TESTING = false;

  private static int totalThreads = 0;

  String host;
  int port;
  WriteOptions writeOptions;
  String user;
  String pwd;

  Level debuglevel;

  int bulkInsertSize = DEFAULT_BULKINSERT_SIZE;

  private final Logger logger = Logger.getLogger(ConfigUtil.LINKBENCH_LOGGER);

  private RocksService getRocksClient() throws Exception {
    if (rocksClient.get() == null) {
      try {
        rocksClient.set(clientManager.createClient(
          new FramedClientConnector(fromParts(host, port)),
          RocksService.class).get());
        logger.info("Opened Rocksdb connection to " + host
                    + ":" + port);
      } catch (Exception e) {
        logger.error("Error in open rocksdb to " + host
                     + ":" + port + " " + e);
        throw e;
      }
    }
    return rocksClient.get();
  }

  static synchronized void incrThreads() {
     totalThreads++;
  }

  static synchronized boolean isLastThread() {
    if (--totalThreads == 0) {
      return true;
    } else {
      return false;
    }
  }

  @Override
  public void close() {
    try {
      if (!isLastThread()) {
        return;
      }
      if (clientManager != null) {
        clientManager.close();
      }
    } catch (Exception ioex) {
      logger.error("Error while closing client connection: " + ioex);
    }
  }

  @Override
  public void initialize(Properties p, Phase currentPhase, int threadId)
      throws IOException, Exception {
    incrThreads();
    host = ConfigUtil.getPropertyRequired(p, CONFIG_HOST);
    port = ConfigUtil.getInt(p, CONFIG_PORT);
    writeOptions = new WriteOptions();
    writeOptions.setSync(ConfigUtil.getBool(p, CONFIG_WRITE_SYNC, false));
    writeOptions.setDisableWAL(
      ConfigUtil.getBool(p, CONFIG_WRITE_DISABLE_WAL, false));
    debuglevel = ConfigUtil.getDebugLevel(p);
  }

  public LinkStoreRocksDb() {
    super();
  }

  public LinkStoreRocksDb(Properties props) throws IOException, Exception {
    super();
    initialize(props, Phase.LOAD, 0);
  }

  public void clearErrors(int threadID) {
    logger.warn("Closing Rocksdb connection in threadID " + threadID);
    try {
      if (rocksClient.get() != null) {
        rocksClient.get().close();
        rocksClient.remove();
      }
    } catch (Throwable e) {
      logger.error("Error in Reopen!" + e);
      e.printStackTrace();
    }
  }

  @Override
  public boolean addLink(String dbid, Link l, boolean noinverse)
      throws Exception{
    try {
      return addLinkImpl(dbid, l, noinverse);
    } catch (Exception ex) {
      logger.error("addlink failed! " + ex);
      throw ex;
    }
  }

  private boolean addLinkImpl(String dbid, Link l, boolean noinverse)
      throws Exception {

    if (Level.DEBUG.isGreaterOrEqual(debuglevel)) {
      logger.debug("addLink " + l.id1 +
                         "." + l.id2 +
                         "." + l.link_type);
    }
    AssocVisibility av = AssocVisibility.values()[l.visibility];
    String s = "wormhole...";
    dbid += "assocs";
    long result = getRocksClient().TaoAssocPut(
        dbid.getBytes(), l.link_type, l.id1, l.id2, l.time,
        av, true, Long.valueOf(l.version), l.data, s.getBytes(),
        writeOptions);

    return result == 1;
  }

  /**
   * Internal method: add links without updating the count
   */
  private boolean addLinksNoCount(String dbid, List<Link> links)
      throws Exception {
    if (links.size() == 0)
      return false;

    dbid += "assocs";
    for (Link l:links) {
      AssocVisibility av = AssocVisibility.values()[l.visibility];
      String s = "wormhole...";
      long result =
      getRocksClient().TaoAssocPut(dbid.getBytes(), l.link_type, l.id1,
         l.id2, l.time, av, false, Long.valueOf(l.version), l.data,
         s.getBytes(), writeOptions);
    }
    return true;
}

  @Override
  public boolean deleteLink(String dbid, long id1, long link_type, long id2,
                         boolean noinverse, boolean expunge)
    throws Exception {
    try {
      return deleteLinkImpl(dbid, id1, link_type, id2, noinverse, expunge);
    } catch (Exception ex) {
      logger.error("deletelink failed! " + ex);
      throw ex;
    }
  }

  private boolean deleteLinkImpl(String dbid, long id1, long link_type,
    long id2, boolean noinverse, boolean expunge) throws Exception {
    if (Level.DEBUG.isGreaterOrEqual(debuglevel)) {
      logger.debug("deleteLink " + id1 +
                         "." + id2 +
                         "." + link_type);
    }
    String s = "wormhole...";
    dbid += "assocs";
    long result = getRocksClient().TaoAssocDelete(
      dbid.getBytes() , link_type, id1, id2,
      -1 /*version ignored*/, AssocVisibility.HARD_DELETE, true,
      s.getBytes(), writeOptions);
    return result == 1;
  }

  @Override
  public boolean updateLink(String dbid, Link l, boolean noinverse)
    throws Exception {
    // Retry logic is in addLink
    boolean added = addLink(dbid, l, noinverse);
    return !added; // return true if updated instead of added
  }


  // lookup using id1, type, id2
  @Override
  public Link getLink(String dbid, long id1, long link_type, long id2)
    throws Exception {
    try {
      return getLinkImpl(dbid, id1, link_type, id2);
    } catch (Exception ex) {
      logger.error("getLink failed! " + ex);
      throw ex;
    }
  }

  private Link getLinkImpl(String dbid, long id1, long link_type, long id2)
    throws Exception {
    Link res[] = multigetLinks(dbid, id1, link_type, new long[] {id2});
    if (res == null)
      return null;
    assert(res.length <= 1);
    return res.length == 0 ? null : res[0];
  }


  @Override
  public Link[] multigetLinks(String dbid, long id1, long link_type,
    long[] id2s) throws Exception {
    try {
      return multigetLinksImpl(dbid, id1, link_type, id2s);
    } catch (Exception ex) {
      logger.error("multigetlinks failed! " + ex);
      throw ex;
    }
  }

  private Link[] multigetLinksImpl(String dbid, long id1, long link_type,
    long[] id2s) throws Exception {
    List<Long> l = new ArrayList<Long>();
    for (int i = 0; i < id2s.length; i++) {
      l.add(new Long(id2s[i]));
    }
    dbid += "assocs";
    List<TaoAssocGetEntry> tr = getRocksClient().TaoAssocGetID2s(
        dbid.getBytes(),
      link_type, id1, l);
    Link results[] = new Link[tr.size()];
    int i = 0;
    for (TaoAssocGetEntry tar : tr) {
      results[i] = new Link(id1, link_type, tar.getId2(),
          LinkStore.VISIBILITY_DEFAULT, tar.getData(),
          (int)(tar.getVersion()), tar.getTime());
    }
    return results;
  }

  // lookup using just id1, type
  @Override
  public Link[] getLinkList(String dbid, long id1, long link_type)
    throws Exception {
    return getLinkListImpl(
        dbid, id1, link_type, 0, Long.MAX_VALUE, 0, rangeLimit);
  }

  @Override
  public Link[] getLinkList(String dbid, long id1, long link_type,
    long minTimestamp, long maxTimestamp, int offset, int limit)
    throws Exception {
    try {
      return getLinkListImpl(dbid, id1, link_type, minTimestamp,
                             maxTimestamp, offset, limit);
    } catch (Exception ex) {
      logger.error("getLinkList failed! " + ex);
      throw ex;
    }
  }

  private Link[] getLinkListImpl(String dbid, long id1, long link_type,
    long minTimestamp, long maxTimestamp, int offset, int limit)
    throws Exception {
    dbid += "assocs";
    List<TaoAssocGetEntry> tr = getRocksClient().TaoAssocGetTimeRange(
        dbid.getBytes(), link_type, id1, minTimestamp, maxTimestamp,
        Long.valueOf(offset), Long.valueOf(limit));
    Link results[] = new Link[tr.size()];
    int i = 0;
    for (TaoAssocGetEntry tar : tr) {
      results[i] = new Link(id1, link_type, tar.getId2(),
          LinkStore.VISIBILITY_DEFAULT, tar.getData(),
          (int)(tar.getVersion()), tar.getTime());
      i++;
    }
    return results;
  }

  // count the #links
  @Override
  public long countLinks(String dbid, long id1, long link_type)
    throws Exception {
    try {
      return countLinksImpl(dbid, id1, link_type);
    } catch (Exception ex) {
      logger.error("countLinks failed! " + ex);
      throw ex;
    }
  }

  private long countLinksImpl(String dbid, long id1, long link_type)
    throws Exception {
    dbid += "assocs";
    long count = getRocksClient().TaoAssocCount(
      dbid.getBytes(), link_type, id1);
    if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
      logger.trace("Count result: " + id1 + "," + link_type +
                         " is " + count);
    }
    return count;
  }

  @Override
  public int bulkLoadBatchSize() {
    return bulkInsertSize;
  }

  @Override
  public void addBulkLinks(String dbid, List<Link> links, boolean noinverse)
    throws Exception {
    try {
      addBulkLinksImpl(dbid, links, noinverse);
    } catch (Exception ex) {
      logger.error("addBulkLinks failed! " + ex);
      throw ex;
    }
  }

  private void addBulkLinksImpl(String dbid, List<Link> links,
    boolean noinverse) throws Exception {
    if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
      logger.trace("addBulkLinks: " + links.size() + " links");
    }
    addLinksNoCount(dbid, links);
  }

  @Override
  public void addBulkCounts(String dbid, List<LinkCount> counts)
    throws Exception {
    try {
      addBulkCountsImpl(dbid, counts);
    } catch (Exception ex) {
      logger.error("addbulkCounts failed! " + ex);
      throw ex;
    }
  }

  private void addBulkCountsImpl(String dbid, List<LinkCount> counts)
    throws Exception {
    if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
      logger.trace("addBulkCounts: " + counts.size() + " link counts");
    }
    if (counts.size() == 0)
      return;

    dbid += "assocs";
    for (LinkCount count: counts) {
      getRocksClient().TaoAssocCountPut(
        dbid.getBytes(), count.link_type, count.id1, count.count, null, writeOptions);
    }
  }

  @Override
  public void resetNodeStore(String dbid, long startID) throws Exception {
    //doesn't have a defined utility for Rocksdb
  }

  @Override
  public long addNode(String dbid, Node node) throws Exception {
    try {
      return addNodeImpl(dbid, node);
    } catch (Exception ex) {
      logger.error("addNode failed! " + ex);
      throw ex;
    }
  }

  private long addNodeImpl(String dbid, Node node) throws Exception {
    long ids[] = bulkAddNodes(dbid, Collections.singletonList(node));
    assert(ids.length == 1);
    return ids[0];
  }

  @Override
  public long[] bulkAddNodes(String dbid, List<Node> nodes) throws Exception {
    try {
      return bulkAddNodesImpl(dbid, nodes);
    } catch (Exception ex) {
      logger.error("bulkAddNodes failed! " + ex);
      throw ex;
    }
  }

  private long[] bulkAddNodesImpl(String dbid, List<Node> nodes)
    throws Exception {
    long newIds[] = new long[nodes.size()];
    int i = 0;
    for (Node n : nodes) {
      getRocksClient().TaoFBObjectPut(
        n.id, n.type, (int) n.version, (int) n.version,
        (long) n.time, n.data, true, null, writeOptions);
      newIds[i++] = n.id;
    }
    return newIds;
  }

  @Override
  public Node getNode(String dbid, int type, long id) throws Exception {
    try {
      return getNodeImpl(dbid, type, id);
    } catch (Exception ex) {
      logger.error("getnode failed! " + ex);
      throw ex;
    }
  }

  private Node getNodeImpl(String dbid, int type, long id) throws Exception {
    ReadOptions ropts = new ReadOptions();
    ropts.setVerifyChecksums(true);
    ropts.setFillCache(true);

    TaoFBObjectGetResult rgr = getRocksClient().TaoFBObjectGet(id, type);
    if (!rgr.isFound()) {
      return null; //Node was not found
    } else {
      return new Node(
        id, type, rgr.getVersion(), (int) rgr.getUpdateTime(), rgr.getData());
    }
  }

  @Override
  public boolean updateNode(String dbid, Node node) throws Exception {
    try {
      return updateNodeImpl(dbid, node);
    } catch (Exception ex) {
      logger.error("updateNode failed! " + ex);
      throw ex;
    }
  }

  private boolean updateNodeImpl(String dbid, Node node) throws Exception {
    return addNode(dbid, node) == 1;
  }

  @Override
  public boolean deleteNode(String dbid, int type, long id) throws Exception {
    try {
      return deleteNodeImpl(dbid, type, id);
    } catch (Exception ex) {
      logger.error("deleteNode failed! " + ex);
      throw ex;
    }
  }

  private boolean deleteNodeImpl(String dbid, int type, long id)
    throws Exception {
    getRocksClient().
      TaoFBObjectDel(id, type, null, writeOptions);
    return true;
  }
}
