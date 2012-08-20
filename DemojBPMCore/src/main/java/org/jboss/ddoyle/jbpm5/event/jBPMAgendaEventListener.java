package org.jboss.ddoyle.jbpm5.event;

import org.apache.log4j.Logger;
import org.drools.event.rule.ActivationCancelledEvent;
import org.drools.event.rule.ActivationCreatedEvent;
import org.drools.event.rule.AfterActivationFiredEvent;
import org.drools.event.rule.AgendaEventListener;
import org.drools.event.rule.AgendaGroupPoppedEvent;
import org.drools.event.rule.AgendaGroupPushedEvent;
import org.drools.event.rule.BeforeActivationFiredEvent;
import org.drools.event.rule.RuleFlowGroupActivatedEvent;
import org.drools.event.rule.RuleFlowGroupDeactivatedEvent;
import org.drools.runtime.KnowledgeRuntime;
import org.drools.runtime.StatefulKnowledgeSession;

/**
 * Drools {@link AgendaEventListener} which triggers firing of rules when a <code>Drools RuleFlowGroup</code> has been activated.
 * <p/>
 * Current implementation is required to use RuleTask nodes in the jBPM5 process. The RuleNode activates the configured RuleFlowGroup, but
 * does not fire the rules. This listener picks up the RuleFlowGroupActivedEvent and will trigger the knowledgesession to fire the rules. 
 * 
 * @author <a href="mailto:duncan.doyle@redhat.com">Duncan Doyle</a>
 */
public class jBPMAgendaEventListener implements AgendaEventListener {

	private static final Logger LOGGER = Logger.getLogger(jBPMAgendaEventListener.class);
	
	public void activationCreated(ActivationCreatedEvent event) {
	}

	public void activationCancelled(ActivationCancelledEvent event) {
	}

	public void beforeActivationFired(BeforeActivationFiredEvent event) {
	}

	public void afterActivationFired(AfterActivationFiredEvent event) {
	}

	public void agendaGroupPopped(AgendaGroupPoppedEvent event) {
	}

	public void agendaGroupPushed(AgendaGroupPushedEvent event) {
	}

	public void beforeRuleFlowGroupActivated(RuleFlowGroupActivatedEvent event) {
	}

	public void afterRuleFlowGroupActivated(RuleFlowGroupActivatedEvent event) {
		KnowledgeRuntime runtime = event.getKnowledgeRuntime();
		if (runtime instanceof StatefulKnowledgeSession) {
			((StatefulKnowledgeSession) runtime).fireAllRules();
		} else {
			throw new IllegalStateException("This AgendaEventListener should only be used with a StatefulKnowledgeSession as the knowledge runtime.");
		}
	}

	public void beforeRuleFlowGroupDeactivated(RuleFlowGroupDeactivatedEvent event) {
	}

	public void afterRuleFlowGroupDeactivated(RuleFlowGroupDeactivatedEvent event) {
	}

	
}
