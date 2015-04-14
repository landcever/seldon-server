/*
 * Seldon -- open source prediction engine
 * =======================================
 *
 * Copyright 2011-2015 Seldon Technologies Ltd and Rummble Ltd (http://www.seldon.io/)
 *
 * ********************************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ********************************************************************************************
 */

/* Generated by Together */

package io.seldon.db.jdo;

import io.seldon.api.Constants;
import io.seldon.api.state.ClientConfigHandler;
import io.seldon.api.state.NewClientListener;
import io.seldon.db.jdbc.JDBCConnectionFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;
import javax.jdo.JDOHelper;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;
import javax.naming.NamingException;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JDOFactory implements NewClientListener, DbConfigHandler
{
	private static final Logger logger = Logger.getLogger( JDOFactory.class.getName() );
	private static final String DEFAULT_DB_JNDI_NAME = "java:comp/env/jdbc/ClientDB";
	private static final String DEFAULT_API_JNDI_NAME = "java:comp/env/jdbc/ApiDB";

	private JDOPMRetriever pmRet = new JDOPMRetriever();
    private Map<String, PersistenceManagerFactory> factories = new ConcurrentHashMap<>();
    private Map<String,String>  clientJNDINames = new ConcurrentHashMap<>();
    private Map<String,String>  clientToDBName = new ConcurrentHashMap<>();

    @Autowired
    private Properties dataNucleusProperties;

	@Autowired
	private JDBCConnectionFactory jdbcConnectionFactory;

	@Autowired
	private ClientConfigHandler clientConfigHandler;
	private static JDOFactory jdoFactory;
	private List<DbConfigListener> listeners = new ArrayList<>();


	@PostConstruct
	public void intialise() throws NamingException {
		clientConfigHandler.addNewClientListener(this, true);
		registerFactory("api", "api", DEFAULT_API_JNDI_NAME);
		jdbcConnectionFactory.addDataSource("api", DEFAULT_API_JNDI_NAME, "api");
		jdoFactory = this;
	}
//    @PostConstruct
//    public void initialise(Properties props, Properties jdoProperties) throws NamingException {
//        for (Object key : jdoProperties.keySet()) {
//            String dbKey = (String) key;
//            String value = jdoProperties.getProperty(dbKey);
//            String[] values = value.split(",");
//            String connectionFactoryName = null;
//            String dbName = null;
//            if (values.length == 1)
//            {
//            	dbName = dbKey;
//            	connectionFactoryName = value;
//            }
//            else if (values.length == 2)
//            {
//            	dbName = values[0];
//            	connectionFactoryName = values[1];
//            }
//            else
//            	throw new JDOStartupException("Bad jdofactories.properties file");
//
//            clientJNDINames.put(dbKey, connectionFactoryName);
//            clientToDBName.put(dbKey, dbName);
//            registerFactory(props, dbKey, dbName, connectionFactoryName);
//        }
//        JDBCConnectionFactory.initialise(clientJNDINames,clientToDBName);
//    }
    
    public String getJNDIForClient(String client)
    {
    	return clientJNDINames.get(client);
    }

//    public static void initialise(Properties jdoProperties, String clientName, String databaseName, String jndiResource) throws NamingException {
//    	if (databaseName == null)
//    		databaseName = clientName;
//        registerFactory(jdoProperties, clientName, databaseName, jndiResource);
//        clientJNDINames.put(clientName, jndiResource);
//        clientToDBName.put(clientName, databaseName);
//		jdbcConnectionFactory.initialise(clientJNDINames,clientToDBName);
//    }

    private void registerFactory(String clientName, String databaseName, String jndiResource) {
    	Properties connectionProperties = (Properties) dataNucleusProperties.clone();
		connectionProperties.setProperty("javax.jdo.option.ConnectionFactoryName", jndiResource);
    	if (databaseName != null)
    		connectionProperties.setProperty("datanucleus.mapping.Catalog", databaseName);
    	logger.info("Adding PMF factory for client "+clientName+" with database "+databaseName+" with JNDI Datasource:"+jndiResource);
        PersistenceManagerFactory factory = JDOHelper.getPersistenceManagerFactory(connectionProperties);
        factories.put(clientName, factory);
    }

    /**
	 * 	Return the singleton persistence manager factory instance.
	 */
	public synchronized PersistenceManagerFactory getPersistenceManagerFactory ()
	{
		if (factories.size() == 1)
			return factories.values().iterator().next();
		else
			return null;
	}

	public boolean isDefaultClient(String key)
	{
		return !factories.containsKey(key);
	}
    
	public PersistenceManager getPersistenceManager(String key)
    {
	    PersistenceManagerFactory pmf = factories.get(key);
	    if (pmf == null)
	    {
	    	pmf = factories.get(Constants.DEFAULT_CLIENT);
	    }
	    if (pmf != null)
	    {
	    	PersistenceManager pm = (PersistenceManager) pmRet.getPersistenceManager(key,pmf);
	    	if (!pm.currentTransaction().isActive())
	    		TransactionPeer.startReadOnlyTransaction(pm);
	    	return pm;
	    }
	    else
	    	return null;
    }
    
    public void cleanupPM()
    {
        pmRet.cleanup();
    }

	@Override
	public void clientAdded(String client, Map<String, String> initialConfig) {
		String jndiName = initialConfig.get("DB_JNDI_NAME");

		if(jndiName==null)
			jndiName = DEFAULT_DB_JNDI_NAME;

		String dbName = initialConfig.get("DB_NAME");

		if(dbName==null)
			dbName = client;

		logger.info("Adding client "+client+" JNDI="+jndiName+" dbName="+dbName);
		registerFactory(client,dbName,jndiName);
		try {
			jdbcConnectionFactory.addDataSource(client,jndiName,dbName);
			for(DbConfigListener listener: listeners){
				listener.dbConfigInitialised(client);
			}
		} catch (NamingException e) {
			logger.error("Couldn't add data source for client : " + client +
					" jndi name " + jndiName + " and db name " + dbName,e);
		}
	}

	@Override
	public void clientDeleted(String client) {
		logger.info("Removing PM factory for "+client);
		factories.get(client).close();
		factories.remove(client);
	}

	public static JDOFactory get(){
		return jdoFactory;
	}

	@Override
	public void addDbConfigListener(DbConfigListener listener) {
		listeners.add(listener);
	}
}
