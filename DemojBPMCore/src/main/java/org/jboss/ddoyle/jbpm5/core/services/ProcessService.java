package org.jboss.ddoyle.jbpm5.core.services;

import java.util.Map;

import org.drools.runtime.StatefulKnowledgeSession;

public interface ProcessService {
	
	
	public static final String PROCESS_INSTANCE_ID = "processInstanceId";
	
	public Map<String, Object> startProcess(String processId, Map<String, Object> parameters);
	
	public int getKSessionIdForProcess(final long processInstanceID);
	
	public StatefulKnowledgeSession getKSessionForProcess(final long processInstanceID);

}
