package org.jboss.ddoyle.jbpm5.core.workitemhandler;

import java.util.Map;

import org.drools.runtime.process.WorkItemHandler;

/**
 * Stateful <code>builder</code> which is responsible for building <code>jBPM</code> {@link WorkItemHandlers}.
 * 
 * @author <a href="mailto:duncan.doyle@redhat.com">Duncan Doyle</a>
 */
public interface WorkItemHandlerBuilder {

	public abstract void addWorkItemHandler(WorkItemHandler wih, String wihName);
	
	public abstract void addWorkItemHandler(Class<WorkItemHandler> clazz, String wihName);
	
	public abstract void addWorkItemHandler(String wihClazzName, String wihName);
	
	public abstract Map<String, WorkItemHandler> buildWorkItemHandlers();

}
