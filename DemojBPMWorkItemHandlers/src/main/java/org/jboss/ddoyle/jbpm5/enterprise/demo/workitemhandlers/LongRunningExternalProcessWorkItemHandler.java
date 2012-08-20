package org.jboss.ddoyle.jbpm5.enterprise.demo.workitemhandlers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;

import org.apache.log4j.Logger;
import org.drools.runtime.process.WorkItem;
import org.drools.runtime.process.WorkItemHandler;
import org.drools.runtime.process.WorkItemManager;
import org.jboss.ddoyle.jbpm5.entity.WorkItemXref;


/**
 * Represents a long running external task which should cause the process instance to reach a safe-point and persist.
 * <p/>
 * The idea here is to simulate a long running process, which receives an ID with which it can do a callback into the process engine to mark
 * the workitem as complete.
 * <p/>
 * Such async {@link WorkItemHandler WorkItemHandlers} usually consist of 2 components. The actual {@link WorkItemHandler} which sends the
 * request to the external system. This request needs to contain some sort of correlation-id which we can later use to correlate a callback
 * from the external system and continue the process. The second component is a receiving component, which receives the signal from the
 * external system and which should trigger the continuation of the process. The receiving component needs to have a mechanism to deal with
 * duplicate signals being delivered in case of a failure during the signaling process.
 * 
 * @author <a href='mailto:duncan.doyle@redhat.com'>Duncan Doyle</a>
 */
public class LongRunningExternalProcessWorkItemHandler implements WorkItemHandler {

	private static final Logger LOGGER = Logger.getLogger(LongRunningExternalProcessWorkItemHandler.class);

	private static final String DB_CONNECTION_JNDI_NAME = "java:ExternalSystemDS";

	private static final String PREPARED_STATEMENT_SQL = "INSERT INTO processing_request (id, jbpm_reference_uuid) VALUES (nextval('processing_request_seq'), ?)";

	private static final String WORKITEM_XREF_PREPARED_STATEMENT_SQL = "INSERT INTO workitemxref (id, workitem_id, workitem_uuid) VALUES (nextval('workitemxref_seq'), ?)";

	private static final String PROCESS_INSTANCE_UUID_PARAMETER_NAME = "process_instance_uuid";

	// TODO: Crap, this is not an EJB, so we cannot inject the persistence unit.
	/*
	 * @PersistenceContext(unitName="jBPM5Persistence.jpa") private EntityManager em;
	 */
	private EntityManagerFactory emf;

	private static final String EM_JNDI_NAME = "java:/jBPM5EntityManagerFactory";

	private UserTransaction utx;

	private static final String UTX_JNDI_NAME = "UserTransaction";

	/**
	 * The naming context.
	 */
	private InitialContext ic;

	public LongRunningExternalProcessWorkItemHandler() {
		// Lookup and cache the ic.
		try {
			this.ic = new InitialContext();
			/*
			 * TODO I still don't really understand whether injecting (or looking up) an EntityManager in a non-EJB is thread-safe.
			 * According to my knowledge, the EntityManager we retrieve from JNDI is actually a proxy. It deals with binding EntityManager
			 * to current thread, current transaction, etc. So, in theory, if the EM is bound to the current transaction and the current
			 * transaction is bound to the current thread, that would mean that the actual underlying EM will not be used in multiple
			 * threads concurrently, which would imply that this is a thread-safe aproach.
			 * 
			 * But everywhere on the web you see that injecting a PersistenceContext into a Servlet is not thread safe, as the Servlet can
			 * be accessed concurrently by multiple threads. However, if the PersistenceContext retrieved from JNDI is JTA Transaction and
			 * thus Thread bound, how can that be thread unsafe?
			 * 
			 * I just don't want to create a new EntityManager when it is not really necessary, i.e. when I can use an application-server
			 * managed onde.
			 */
			emf = (EntityManagerFactory) ic.lookup(EM_JNDI_NAME);
			utx = (UserTransaction) ic.lookup(UTX_JNDI_NAME);
		} catch (NamingException ne) {
			throw new RuntimeException("Error while looking up InitialContext.");
		}
	}

	@Override
	public void abortWorkItem(WorkItem arg0, WorkItemManager arg1) {
		throw new IllegalStateException("This WorkItem cannot be aborted.");

	}

	@Override
	public void executeWorkItem(WorkItem workItem, WorkItemManager workItemManager) {
		/*
		 * TODO: This should actually call an external system. We're are currently simulating that by adding a record to a database.
		 * 
		 * Don't need any fancy JPA stuff for now, we'll just use a simple DB Connection. We will use a JTA Transaction however, as we want
		 * this DB update to happen in the same transaction as the one in which our process runs.
		 * 
		 * TODO: This is actually something that one needs to be aware of when designing such systems. If the process runs in a JTA
		 * transaction, and you trigger an external system via a non-transactional mechanims, you could have a situation in which a long
		 * running external process is started (i.e. triggered by a REST call or WebServices call), but if after that call an error occurs,
		 * the process instance will not be peristsed in the DB ..... What's even worse, you will actually never know that it once started
		 * (at least not from the jBPM5 loggers and persistency, as everything that is written to the DB will be rolled back.
		 * 
		 * There are 2 possible architecture here: 1) We always trigger an external system via a tranasctional mechanims (record in DB,
		 * message, etc). 2) We will always retry to start the process, and the external system is designed to cope with duplicate process
		 * triggering.
		 */
		Connection connection = null;
		try {
			// The database insert we're doing here is actually sending a message to the remote. This should be seen as a remote system
			// call.
			DataSource ds = (DataSource) ic.lookup(this.DB_CONNECTION_JNDI_NAME);
			connection = ds.getConnection();
			PreparedStatement ps = connection.prepareStatement(PREPARED_STATEMENT_SQL);
			ps.setString(1, getJBPMReferenceUuid(workItem));

			boolean psResult = ps.execute();
			if (ps.getUpdateCount() != 1) {
				throw new RuntimeException("Error while triggering remote long running process.");
				// TODO: We should return an error message here to the process, so the process can take appopriate functional action.
				// workItemManager.completeWorkItem(arg0, arg1)
			}
		} catch (NamingException ne) {
			throw new RuntimeException("Unable to find datasource connection.", ne);
		} catch (SQLException sqle) {
			throw new RuntimeException("Error creating prepared statement.", sqle);
		} finally {
			if (connection != null) {
				try {
					connection.close();
				} catch (SQLException sqle) {
					// Nothing we can do here. Just log the error and continue.
					LOGGER.error("Error while closing datasource connection.");
				}
			}
		}

	}

	/**
	 * Retrieves the current sessionProcessInstanceXRefID. This is the key we will send to the external system with which it can reference
	 * the current process instance.
	 * 
	 * @return the reference UUID for this workitem.
	 */
	private String getJBPMReferenceUuid(final WorkItem workItem) {
		String workItemXrefUUID;
		EntityManager em = emf.createEntityManager();
		/*
		 * TODO EM should be bound to the current running transaction. Is there a way we can check that? Will it fail when no transaction is
		 * running?
		 */
		try {
			em.joinTransaction();

			workItemXrefUUID = UUID.randomUUID().toString();

			// We store the UUID as well before returning it.
			WorkItemXref workitemXref = new WorkItemXref(workItem.getId(), workItemXrefUUID);
			em.persist(workitemXref);
		} finally {
			em.close();
		}
		return workItemXrefUUID;
	}
}
