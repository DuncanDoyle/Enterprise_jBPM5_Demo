package org.jboss.ddoyle.jbpm5.enterprise.demo.ejb;

import javax.jms.Message;
import javax.jms.MessageListener;
import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;

/**
* Message-Driven Bean implementation class for: CallbackMDB
* 
* @author <a href='mailto:duncan.doyle@redhat.com'>Duncan Doyle</a>
*/
@MessageDriven(name = "CallbackMDB", activationConfig = {
		@ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue"),
		@ActivationConfigProperty(propertyName = "destination", propertyValue = "queue/jBPM5CallbackQueue"),
		@ActivationConfigProperty(propertyName = "useDLQ", propertyValue = "false") })
public class WSHumanTaskResponseHandlerMDB implements MessageListener {

	public void onMessage(Message arg0) {
		// TODO Auto-generated method stub

	}

}
