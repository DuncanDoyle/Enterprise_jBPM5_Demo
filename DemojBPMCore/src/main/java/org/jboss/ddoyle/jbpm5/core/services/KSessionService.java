package org.jboss.ddoyle.jbpm5.core.services;

import org.drools.runtime.StatefulKnowledgeSession;

public interface KSessionService {
	
	public static final String KSESSION_ID = "knowledgeSessionId";
	
	public abstract StatefulKnowledgeSession getKSession();
	
	public abstract StatefulKnowledgeSession loadKSession(int ksessionId);

}
