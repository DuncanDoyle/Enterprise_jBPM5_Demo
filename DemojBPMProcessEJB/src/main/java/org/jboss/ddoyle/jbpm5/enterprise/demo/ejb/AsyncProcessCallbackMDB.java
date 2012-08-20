package org.jboss.ddoyle.jbpm5.enterprise.demo.ejb;

import javax.annotation.Resource;
import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.ejb.MessageDrivenContext;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.apache.log4j.Logger;
import org.drools.persistence.info.WorkItemInfo;
import org.drools.runtime.StatefulKnowledgeSession;
import org.drools.runtime.process.WorkItemManager;
import org.jboss.ddoyle.jbpm5.core.services.KSessionService;
import org.jboss.ddoyle.jbpm5.core.services.SimpleKSessionService;
import org.jboss.ddoyle.jbpm5.entity.SessionProcessXref;
import org.jboss.ddoyle.jbpm5.entity.WorkItemXref;
import org.jbpm.ee.CMTDisposeCommand;



/**
 * Message-Driven Bean implementation class for: CallbackMDB
 * 
 * @author <a href='mailto:duncan.doyle@redhat.com'>Duncan Doyle</a>
 */
@MessageDriven(name = "CallbackMDB", activationConfig = {
		@ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue"),
		@ActivationConfigProperty(propertyName = "destination", propertyValue = "queue/jBPM5CallbackQueue"),
		@ActivationConfigProperty(propertyName = "useDLQ", propertyValue = "false") })
public class AsyncProcessCallbackMDB implements MessageListener {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = Logger.getLogger(AsyncProcessCallbackMDB.class);

	/**
	 * The jBPM5 JPA <code>PersistenceContext</code>/{@link EntityManager}.
	 */
	@PersistenceContext(unitName = "jBPM5Persistence.jpa")
	private EntityManager em;

	@Resource
	private MessageDrivenContext mdCtx;

	/**
	 * Default constructor.
	 */
	public AsyncProcessCallbackMDB() {
	}

	/**
	 * @see MessageListener#onMessage(Message)
	 */
	public void onMessage(Message message) {
		/*
		 * TODO: We need to define a message format for these Callback messages. For now, we will just assume that we get the
		 * process_instance_uuid in a TextMessage.
		 */
		if (!(message instanceof TextMessage)) {
			throw new IllegalArgumentException("We expect a TextMessage, but got: " + message.getClass().getCanonicalName());
		}

		String workItemReferenceUuid;
		try {
			workItemReferenceUuid = ((TextMessage) message).getText();
		} catch (JMSException jmse) {
			throw new RuntimeException("Error retrieving Process Instance UUID from JMS Message");
		}

		// First retrieve the WorkItemID.
		Query workItemQuery = em.createQuery("SELECT w FROM WorkItemXref w WHERE w.workitem_uuid = :workitem_uuid");
		workItemQuery.setParameter("workitem_uuid", workItemReferenceUuid);

		WorkItemXref workItemXref = (WorkItemXref) workItemQuery.getSingleResult();

		if (workItemXref == null) {
			// TODO When we encounter a UUID of a non-existing workitem, we should return a more friendly error messsage.
			throw new IllegalArgumentException("Unable to find WorkItem for WorkItem UUID: " + workItemReferenceUuid);
		}

		/*
		 * Now, retrieve the correct session xref. We've got the workitem id, so we need to use the workiteminfo table to retrieve the
		 * process instance id, from which we can retrieve the correct session to load.
		 */
		WorkItemInfo workItemInfo = em.find(WorkItemInfo.class, workItemXref.getWorkitem_id());

		if (workItemInfo == null) {
			/*
			 * TODO Review this. This seems like a correct error message to return when we are able to find that workitem uuid, but cannot
			 * find the actual workitem.
			 */
			throw new IllegalStateException("Cannot find WorkItem with id: " + workItemXref.getWorkitem_id()
					+ ". This should never happen. jBPM5 runtime is in an illegal state.");
		}

		// Now we need to find the correct StatefulKnowledgeSessionID.
		long processInstanceId = workItemInfo.getProcessInstanceId();
		// SessionProcessXref spXref = em.find(SessionProcessXref.class, arg1);
		Query q = em.createQuery("select s FROM SessionProcessXref s where s.processInstanceId = :processInstanceId");
		q.setParameter("processInstanceId", processInstanceId);
		SessionProcessXref spXref = (SessionProcessXref) q.getSingleResult();

		if (spXref == null) {
			throw new IllegalStateException("No process instance found for UUID: " + workItemReferenceUuid
					+ ". Process has either already finished or process instance store is in illegal state");
		}

		int sessionId = spXref.getSessionId();

		KSessionService ksessionService = SimpleKSessionService.getInstance();
		// TODO: We still need to implement session loading.
		final StatefulKnowledgeSession ksession = ksessionService.loadKSession(sessionId);

		// Retrieve the WorkItemManager and complete the work.
		WorkItemManager manager = ksession.getWorkItemManager();
		manager.completeWorkItem(workItemXref.getWorkitem_id(), null);
		
		//And delete the WorkItemXref when we're done.
		em.remove(workItemXref);
		

		// We're done, dispose the KSession.
		ksession.execute(new CMTDisposeCommand());
	}

}
