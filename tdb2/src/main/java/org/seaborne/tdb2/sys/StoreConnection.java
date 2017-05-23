/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  See the NOTICE file distributed with this work for additional
 *  information regarding copyright ownership.
 */

package org.seaborne.tdb2.sys ;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet ;
import java.util.Map ;
import java.util.Set ;
import java.util.concurrent.ConcurrentHashMap ;

import org.apache.jena.atlas.io.IO;
import org.apache.jena.atlas.lib.FileOps;
import org.apache.jena.sparql.core.DatasetGraph ;
import org.seaborne.dboe.base.file.ChannelManager ;
import org.seaborne.dboe.base.file.Location ;
import org.seaborne.dboe.base.file.ProcessFileLock;
import org.seaborne.dboe.sys.Names;
import org.seaborne.tdb2.setup.StoreParams ;
import org.seaborne.tdb2.setup.TDBBuilder ;
import org.seaborne.tdb2.store.DatasetGraphTDB ;

/** A StoreConnection is the reference to the underlying storage.
 *  There is JVM-wide cache of backing datasets.
 */
public class StoreConnection
{
    private static Map<Location, StoreConnection> cache = new ConcurrentHashMap<>() ;
    
    /** Get the {@code StoreConnection} to a location, 
     *  creating the storage structures if it does not exist. */ 
    public synchronized static StoreConnection connectCreate(Location location) {
        return connectCreate(location, null) ;
    }

    /** Get the {@code StoreConnection} to a location, 
     *  creating the storage structures if it does not exist.
     *  Use the provided {@link StoreParams} - any persistent setting 
     *  already at the location take precedence.
     */ 
    public synchronized static StoreConnection connectCreate(Location location, StoreParams params) {
        return make(location, params) ;
    }

    /** Get the {@code StoreConnection} for a location, but do not create it.
     *  Returns null for "not setup". 
     */
    public synchronized static StoreConnection connectExisting(Location location) {
        StoreConnection sConn = cache.get(location) ;
        return sConn ;
    }

    public synchronized static boolean isSetup(Location location) {
        return cache.containsKey(location) ;
    }

    /** Make a StoreConnection based on any StoreParams at the location or the system defaults. */
    private static StoreConnection make(Location location) {
        return make(location, null) ;
    }

    /**
     * Return a {@code StoreConnection} for a particular location,
     * creating it if it does not exist in storage.
     */
    private synchronized static StoreConnection make(Location location, StoreParams params) {
        StoreConnection sConn = cache.get(location) ;
        if ( sConn == null ) {
            ProcessFileLock lock = null;
            if (SystemTDB.DiskLocationMultiJvmUsagePrevention && ! location.isMem() ) {
                lock = lockForLocation(location);
                // Take the lock.  This is atomic.
                lock.lockEx();
            }
            
            // Recovery happens when TransactionCoordinator.start is called
            // during the building of the DatasetGraphTxn.
            
            DatasetGraphTDB dsg = (DatasetGraphTDB)TDBBuilder.build(location, params) ;
            
            sConn = new StoreConnection(dsg, lock) ;
            if (!location.isMemUnique())
                cache.put(location, sConn) ;
        }
        return sConn ;
    }
    
    /** Stop managing all locations. Use with great care. */
    public static synchronized void reset() {
        // Copy to avoid potential CME.
        Set<Location> x = new HashSet<>(cache.keySet()) ;
        for (Location loc : x)
            expel(loc, true) ;
        cache.clear() ;
        ChannelManager.reset(); 
    }

    /** Stop managing a location. There should be no transactions running. */
    public static synchronized void release(Location location) {
        expel(location, false) ;
    }

    /** Stop managing a location. Use with great care (testing only). */
    public static synchronized void expel(Location location, boolean force) {
        StoreConnection sConn = cache.get(location) ;
        if (sConn == null) return ;

        //    if (!force && sConn.transactionManager.activeTransactions()) 
        //        throw new TDBTransactionException("Can't expel: Active transactions for location: " + location) ;

        // No transactions at this point 
        // (or we don't care and are clearing up forcefully.)
        
        sConn.getDatasetGraphTDB().getTxnSystem().getTxnMgr().shutdown();
        sConn.getDatasetGraphTDB().shutdown() ;
        
        sConn.isValid = false ;
        cache.remove(location) ;

        // Release the lock after the cache is emptied.
        if (SystemTDB.DiskLocationMultiJvmUsagePrevention && ! location.isMem() ) {
            if ( ! sConn.lock.isLockedHere() )
                SystemTDB.errlog.warn("Location " + location.getDirectoryPath() + " was not locked by this process.");
            sConn.lock.unlock();
            ProcessFileLock.release(sConn.lock);
        }
    }

    /** Create or fetch a {@link ProcessFileLock} for a Location */
    public static ProcessFileLock lockForLocation(Location location) {
        FileOps.ensureDir(location.getDirectoryPath());
        String lockFilename = location.getPath(Names.TDB_LOCK_FILE);
        Path path = Paths.get(lockFilename);
        try {
            path.toFile().createNewFile();
        } catch(IOException ex) { IO.exception(ex); return null; }
        return ProcessFileLock.create(lockFilename);
    }
    
    private final DatasetGraphTDB   datasetGraph ;
    private final Location          location ;
    private final ProcessFileLock   lock ;
    private boolean                 isValid = true ;
    private volatile boolean        haveUsedInTransaction = false ;

    private StoreConnection(DatasetGraphTDB dsg, ProcessFileLock fileLock)
    {
        this.datasetGraph = dsg ;
        this.location = dsg.getLocation() ;
        this.lock = fileLock;
    }

    public DatasetGraph getDatasetGraph() {
        return datasetGraph;
    }

    public DatasetGraphTDB getDatasetGraphTDB() {
        return datasetGraph;
    }
    
    public Location getLocation() {
        return location ;
    }
    
    public ProcessFileLock getLock() {
        return lock ;
    }

}

