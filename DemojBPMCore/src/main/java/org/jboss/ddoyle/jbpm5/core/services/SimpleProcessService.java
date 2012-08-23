package org.jboss.ddoyle.jbpm5.core.services;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;
import javax.transaction.UserTransaction;

import org.apache.log4j.Logger;
import org.drools.runtime.StatefulKnowledgeSession;
import org.drools.runtime.process.ProcessInstance;
import org.jboss.ddoyle.jbpm5.entity.SessionProcessXref;
import org.jbpm.ee.CMTDisposeCommand;

public class SimpleProcessService implements ProcessService {

	public static final String PROCESS_INSTANCE_UUID_VARIABLE_NAME = "process_instance_uuid";

	private static final Logger LOGGER = Logger.getLogger(SimpleProcessService.class);

	/**
	 * Singleton.
	 */
	private static ProcessService instance = new SimpleProcessService();

	private InitialContext ic;

	private UserTransaction utx;

	private TransactionManager tm;

	private EntityManagerFactory jbpmCoreEMF;

	private SimpleProcessService() {
		// Load the InitialContext and UserTransaction.
		try {
			ic = new InitialContext();
			utx = (UserTransaction) ic.lookup("UserTransaction");
			if (utx == null)
				throw new IllegalStateException("JNDI lookup of user transaction failed.");
		} catch (NamingException ne) {
			throw new RuntimeException("Error while creating InitialContext.");
		}

		try {
			tm = (TransactionManager) ic.lookup("java:/TransactionManager");
			if (tm == null)
				throw new IllegalStateException("JNDI lookup of transaction manager failed.");
		} catch (Exception e) {
			e.printStackTrace();
			throw new IllegalStateException("JNDI lookup of transaction manager failed.");
		}
		/*
		 * TODO: We need to abstract this in some sort of factory (abstract-factory), or we need to do some injection (Spring, CDI). We are
		 * probably better off developing this stuff on AS7, so let's convert to some CDI soon.
		 */
		try {
			jbpmCoreEMF = (EntityManagerFactory) ic.lookup("java:/jBPM5EntityManagerFactory");
		} catch (Exception e) {
			throw new IllegalStateException("JNDI lookup of entity manager factory failed.");
		}

	}

	/**
	 * @return the <code>Singleton</code> instance of this KnowledgeSessionService.
	 */
	public static ProcessService getInstance() {
		return instance;
	}

	public Map<String, Object> startProcess(String processId, Map<String, Object> parameters) {
		/*
		 * Create a new process instance UUID. This is to not be tied to any DB generated sequence ids, etc. It will for example be used to
		 * communicate the process instance ID with external systems. This also serves as a form of abstraction of the underlying BPM
		 * technology for external systems.
		 */
		UUID processInstanceUUID = UUID.randomUUID();

		// As we are starting a new process, first, get a new StatefulKnowledgeSession.

		// This thing should always be run in a Transactional Context, so check whether a transaction is running.
		// TODO: This is a bit dirty, if a transaction is not running, we could actually start one ourselves. Just being lazy for now.
		try {
			if (utx.getStatus() != Status.STATUS_ACTIVE) {
				throw new IllegalStateException("This method requires an active JTA transaction.");
			}
		} catch (SystemException e) {
			throw new RuntimeException("Error get UserTransaction status.");
		}

		final StatefulKnowledgeSession ksession = SimpleKSessionService.getInstance().getKSession();
		int ksessionId = ksession.getId();

		StringBuilder sBuilder = new StringBuilder();
		sBuilder.append("startProcessAndReturnId()\tsessionId :  " + ksessionId + " : process = " + processId);

		ProcessInstance pInstance = null;

		// Build the parameters.
		// TODO: This method has become a lot less generic. Every process now has to define a process instance UUID as its process param. Is
		// that really what we want?
		if (parameters == null) {
			parameters = new HashMap<String, Object>();
		}
		/*
		 * Add the process instance UUID. This is the same ID we use in the SessionProcessXref, so when we pass this to an external system
		 * on an async call, we can retrieve the correct SessionProcessXref object, and thus are able to load the correct SKS, and signal
		 * the correct process instance.
		 */
		parameters.put(PROCESS_INSTANCE_UUID_VARIABLE_NAME, processInstanceUUID.toString());

		if (parameters != null) {
			pInstance = ksession.startProcess(processId, parameters);
		} else {
			/*
			 * TODO: Because we now always send the proces_instance_uuid as param to the process, this is dead. If we decide to always use a
			 * UUID, this code can be removed.
			 */
			pInstance = ksession.startProcess(processId);
		}

		Map<String, Object> returnMap = new HashMap<String, Object>();
		returnMap.put(ProcessService.PROCESS_INSTANCE_ID, pInstance.getId());
		returnMap.put(SimpleProcessService.PROCESS_INSTANCE_UUID_VARIABLE_NAME, processInstanceUUID.toString());
		returnMap.put(KSessionService.KSESSION_ID, ksessionId);

		// Create the EntityManager and have it join the current transaction.
		/*
		 * TODO: Shouldn't we just have the container inject a EntityManager for us? We're currently just a simple POJO, so we can't inject
		 * stuff with default JEE5. Once we run on EAP6, we can use CDI to inject our EntityManager (or EntityManagerFactory???). Thinking
		 * of it, we could probably do a JNDI lookup of the EntityManager here and thus use a container manager EntityManager.
		 */
		/*
		 * TODO: Do we actually need to create a new EntityManager on every call?
		 */
		EntityManager manager = jbpmCoreEMF.createEntityManager();
		try {
			// Don't need to call 'joinTransaction()' as the EntityManager is created inside the transaction and thus will be automatically
			// registered with the running transaction. (at least, according to the spec).

			SessionProcessXref sksProcessInstanceXRef = new SessionProcessXref(processInstanceUUID.toString());
			sksProcessInstanceXRef.setProcessId(processId);
			sksProcessInstanceXRef.setProcessInstanceId(pInstance.getId());
			sksProcessInstanceXRef.setSessionId(ksessionId);

			manager.persist(sksProcessInstanceXRef);
		} finally {
			// And close the EntityManager.
			manager.close();
		}

		// dispose the KSession using the new CMTDisposeCommand.
		ksession.execute(new CMTDisposeCommand());

		sBuilder.append(" : pInstanceId = " + pInstance.getId() + " : now completed");
		LOGGER.info(sBuilder.toString());
		return returnMap;
	}

	@Override
	public int getKSessionIdForProcess(long processInstanceID) {
		// TODO Implement this method.s
		return 0;
	}

	@Override
	public StatefulKnowledgeSession getKSessionForProcess(long processInstanceID) {
		int ksessionId = getKSessionIdForProcess(processInstanceID);
		return SimpleKSessionService.getInstance().loadKSession(ksessionId);
	}

}
