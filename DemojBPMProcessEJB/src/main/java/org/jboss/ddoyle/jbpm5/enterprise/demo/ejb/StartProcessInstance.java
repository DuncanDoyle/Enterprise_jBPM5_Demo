package org.jboss.ddoyle.jbpm5.enterprise.demo.ejb;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Resource;
import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.ejb.MessageDrivenContext;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

import org.apache.log4j.Logger;
import org.drools.runtime.StatefulKnowledgeSession;
import org.drools.runtime.process.ProcessInstance;
import org.jboss.ddoyle.jbpm5.core.services.KSessionService;
import org.jboss.ddoyle.jbpm5.core.services.ProcessService;
import org.jboss.ddoyle.jbpm5.core.services.SimpleKSessionService;
import org.jboss.ddoyle.jbpm5.core.services.SimpleProcessService;


/**
 * Message-Driven Bean implementation class for: StartProcessInstance.
 * 
 * useDLQ property is set to switch off DLQ handling in the JBoss generic RA. Because we're using HornetQ, this might be redundant.
 * 
 */
@MessageDriven(name = "StartProcessInstance", activationConfig = {
		@ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue"),
		@ActivationConfigProperty(propertyName = "destination", propertyValue = "queue/StartProcessInstanceQueue"),
		@ActivationConfigProperty(propertyName = "useDLQ", propertyValue = "false") })
public class StartProcessInstance implements MessageListener {

	public static final Logger LOGGER = Logger.getLogger(StartProcessInstance.class);

	@Resource
	MessageDrivenContext ctx;

	/**
	 * Default constructor.
	 */
	public StartProcessInstance() {
		}

	/**
	 * @see MessageListener#onMessage(Message)
	 */
	public void onMessage(Message message) {
		try {
			if (message instanceof TextMessage) {
				// First display all processes, so we can see the names of the ids.
				Map dataMap = getProcessData((TextMessage) message);
				
				Map returnMap = SimpleProcessService.getInstance().startProcess("org.jboss.ddoyle.poc.SimplePocProcess", dataMap);
				
				int ksessionId = (Integer) returnMap.get(KSessionService.KSESSION_ID);
				long processInstanceId = (Long) returnMap.get(ProcessService.PROCESS_INSTANCE_ID);
				String processInstanceUUID = (String) returnMap.get(SimpleProcessService.PROCESS_INSTANCE_UUID_VARIABLE_NAME);
				LOGGER.info("Started process instance of process: 'org.jboss.ddoyle.poc.SimplePocProcess' with process-id: '" + processInstanceId
						+ "' in knowledge-session: '" + ksessionId +"'. \n");
			} else {
				/*
				 * TODO: Not a text message. Need to provide some exceptionhandling here. We're not sending a response, as we're async, so
				 * we need another way to provide this error (e.g. notification, start an error process, etc.).
				 */
			}
		} catch (Throwable t) {
			LOGGER.error("Exception was thrown, marking MessageDrivenContext for transaction rollback.", t);
			if (ctx != null) {
				ctx.setRollbackOnly();
			} else {
				throw new IllegalStateException("Unable to rollback transaction, MessageDrivenContext is 'null'.", t);
			}
		}

	}

	private Map<String, Object> getProcessData(TextMessage message) {
		Map<String, Object> processDataMap = new HashMap<String, Object>();
		try {
			processDataMap.put("version", message.getText());
		} catch (JMSException e) {
			throw new RuntimeException("Unable to retrieve version from message.");
		}
		return processDataMap;
	}

}
