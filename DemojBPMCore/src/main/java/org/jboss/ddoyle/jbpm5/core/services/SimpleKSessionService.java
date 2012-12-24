package org.jboss.ddoyle.jbpm5.core.services;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManagerFactory;
import javax.transaction.TransactionManager;
import javax.transaction.UserTransaction;

import org.apache.log4j.Logger;
import org.drools.KnowledgeBase;
import org.drools.KnowledgeBaseFactory;
import org.drools.audit.WorkingMemoryLogger;
import org.drools.builder.KnowledgeBuilder;
import org.drools.builder.KnowledgeBuilderFactory;
import org.drools.builder.ResourceType;
import org.drools.command.Command;
import org.drools.command.SingleSessionCommandService;
import org.drools.event.process.ProcessEventListener;
import org.drools.event.rule.AgendaEventListener;
import org.drools.event.rule.WorkingMemoryEventListener;
import org.drools.io.ResourceFactory;
import org.drools.io.impl.UrlResource;
import org.drools.logger.KnowledgeRuntimeLogger;
import org.drools.logger.KnowledgeRuntimeLoggerFactory;
import org.drools.persistence.jpa.JPAKnowledgeService;
import org.drools.persistence.jpa.JpaJDKTimerService;
import org.drools.persistence.jpa.processinstance.JPAWorkItemManagerFactory;
import org.drools.runtime.Calendars;
import org.drools.runtime.Channel;
import org.drools.runtime.Environment;
import org.drools.runtime.EnvironmentName;
import org.drools.runtime.ExitPoint;
import org.drools.runtime.Globals;
import org.drools.runtime.KnowledgeSessionConfiguration;
import org.drools.runtime.ObjectFilter;
import org.drools.runtime.StatefulKnowledgeSession;
import org.drools.runtime.process.ProcessInstance;
import org.drools.runtime.process.WorkItemHandler;
import org.drools.runtime.process.WorkItemManager;
import org.drools.runtime.rule.Agenda;
import org.drools.runtime.rule.AgendaFilter;
import org.drools.runtime.rule.FactHandle;
import org.drools.runtime.rule.LiveQuery;
import org.drools.runtime.rule.QueryResults;
import org.drools.runtime.rule.ViewChangedEventListener;
import org.drools.runtime.rule.WorkingMemoryEntryPoint;
import org.drools.time.SessionClock;
import org.jboss.ddoyle.jbpm5.core.workitemhandler.WorkItemHandlerBuilder;
import org.jboss.ddoyle.jbpm5.event.jBPMAgendaEventListener;

public class SimpleKSessionService implements KSessionService {

	/*
	 * TODO: implement a method that cleans the persisted knowledgesession from the database table on a 'after process completion' event. We
	 * might want to batch these things up. In that case we could use a timer or something like a cleanup process in a separate thread.
	 * 
	 * We will NOT reuse knowledge sessions. Instead, we will dispose them, and after they have been disposed, we will clean them from the
	 * persistence table. In order to do that, we need to keep track in the database which knowledgesessions have been disposed. To do this,
	 * we keep track of the process-id -> session-id relationship in a separate table (we can create that table after we have started a
	 * process, i.e. in the same transaction). We can than check whether the process instance is still available in the processinstanceinfo
	 * table, if it's not, we remove the associated persisted StatefulKnowledgeSession.
	 */

	/**
	 * Location of the knowledge package to load.
	 */
	private static final String GUVNOR_KNOWLEDGE_PKG_URL_PATH = "http://127.0.0.1:8080/jboss-brms/rest/packages/org.jboss.ddoyle.poc/binary";

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = Logger.getLogger(SimpleKSessionService.class);

	/**
	 * Singleton.
	 */
	private static KSessionService instance = new SimpleKSessionService();

	/**
	 * The Drools {@link KnowledgeBase} which contains the process definitions.
	 */
	private KnowledgeBase kbase;

	/**
	 * The configuration of the knowledge session.
	 */
	private KnowledgeSessionConfiguration ksconfig;

	/**
	 * The EntityManagerFactory for the jBPM5 engine.
	 */
	private EntityManagerFactory jbpmCoreEMF;

	private TransactionManager transactionManager;

	private UserTransaction emfUserTransaction;

	private boolean enableKnowledgeRuntimeFileLogger = true;

	/**
	 * The name of the entityFactory to retrieve.
	 */
	private String entityFactoryName;

	private InitialContext ic;

	/**
	 * Private constructor to prevent initialization of this class.
	 */
	private SimpleKSessionService() {
		// Load the InitialContext;
		try {
			ic = new InitialContext();
		} catch (NamingException ne) {
			throw new RuntimeException("Error while creating InitialContext.");
		}
		transactionManager = getTransactionManager();
		emfUserTransaction = getUserTransaction();
	}

	/**
	 * @return the <code>Singleton</code> instance of this KnowledgeSessionService.
	 */
	public static KSessionService getInstance() {
		return instance;
	}

	/**
	 * Returns a {@link StatefulKnowledgeSession}. If no <code>ksesssionId</code> has been passed to this method, a new
	 * {@link StatefulKnowledgeSession} will be created. If a <code>ksessionId</code> has been passed, the corresponding
	 * {@link StatefulKnowledgeSession} will be loaded using <code>JPA</code>.
	 * 
	 * @param ksessionId
	 * @return
	 */
	public StatefulKnowledgeSession getKSession(Integer ksessionId) {
		return getKSession(ksessionId, null);

	}

	public StatefulKnowledgeSession getKSession(Integer ksessionId, WorkItemHandlerBuilder wihBuilder) {
		synchronized (this) {
			if (kbase == null) {
				KnowledgeBuilder kbuilder = getKnowledgeBuilder();
				kbase = kbuilder.newKnowledgeBase();
			}
		}

		// Every ksession needs to have its own Environment object.
		Environment kEnvironment = getEnvironment();
		synchronized (this) {
			if (ksconfig == null) {
				// TODO Check that we can reuse a KSessionConfig instance over multiple ksessions.
				ksconfig = getKSessionConfig();
			}
		}
		StatefulKnowledgeSession ksession;
		if (ksessionId == null) {
			ksession = JPAKnowledgeService.newStatefulKnowledgeSession(kbase, ksconfig, kEnvironment);
		} else {
			ksession = JPAKnowledgeService.loadStatefulKnowledgeSession(ksessionId, kbase, ksconfig, kEnvironment);
		}
		// Wrap the SKS in a wrapper. This wrapper is used to keep track of loggers registered with the session so they can be closed when
		// the session is disposed.
		StatefulKnowledgeSessionWrapper wrappedKSession = new StatefulKnowledgeSessionWrapper(ksession);

		// Register WorkItemHandlers and EventListeners and initialize the loggers.
		registerWorkItemHandlers(wrappedKSession, wihBuilder);
		registerEventListener(wrappedKSession);
		initializeLoggers(wrappedKSession);
		return wrappedKSession;
	}

	/**
	 * Initializes the WorkingMemoryLoggers and KnowledgeRuntimeLoggers on the session. Adds these loggers to the wrapper object so that
	 * they can be disposed when the session is disposed.
	 */
	private void initializeLoggers(StatefulKnowledgeSessionWrapper ksessionWrapper) {
		// Create the knowledge-logger.
		// klogger = KnowledgeRuntimeLoggerFactory.newThreadedFileLogger(ksession, "test", 1000);
		// KnowledgeRuntimeLoggerFactory.
		/*
		 * TODO: The JpaWorkingMemoryDebLogger seems to be giving issues with PostgreSQL. We need to update to the latest version of this
		 * class as a number of issues are supposed to be fixed.
		 */
		// ksessionWrapper.addWorkingMemoryLogger(new JPAWorkingMemoryDbLogger(ksessionWrapper.getKSession()));

		// ksessionWrapper.addKnowledgeRuntimeLogger(KnowledgeRuntimeLoggerFactory.newConsoleLogger(ksessionWrapper.getKSession()));
		// TODO: This code seriously needs to be cleaned. Referencing properties without using constants ...????
		if (enableKnowledgeRuntimeFileLogger) {
			StringBuilder sBuilder = new StringBuilder();
			sBuilder.append(System.getProperty("jboss.server.log.dir"));
			sBuilder.append("/knowledgeRuntimeLogger-");
			sBuilder.append(ksessionWrapper.getId());
			// kWrapper.setKnowledgeRuntimeLogger(KnowledgeRuntimeLoggerFactory.newFileLogger(ksession, sBuilder.toString()));
			ksessionWrapper.addKnowledgeRuntimeLogger(KnowledgeRuntimeLoggerFactory.newFileLogger(ksessionWrapper.getKSession(),
					sBuilder.toString()));
		}
	}

	/**
	 * Builds the KnowledgeBuilder from the package in Guvnor using the URL {@link #GUVNOR_KNOWLEDGE_PKG_URL_PATH}.
	 * 
	 * @return the {@link KnowledgeBuilder}
	 */
	private KnowledgeBuilder getKnowledgeBuilder() {
		// Initialize the Knowledge Session.
		KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
		UrlResource urlResource = (UrlResource) ResourceFactory.newUrlResource(GUVNOR_KNOWLEDGE_PKG_URL_PATH);
		urlResource.setBasicAuthentication("enabled");
		// TODO: Hardcoded username and password!!! Fix this!!!
		urlResource.setUsername("admin");
		urlResource.setPassword("admin");

		kbuilder.add(urlResource, ResourceType.PKG);
		return kbuilder;
	}

	/**
	 * Creates the ksession's configuration.
	 * 
	 * @return
	 */
	private KnowledgeSessionConfiguration getKSessionConfig() {
		// Define the drools JPA manager factories.
		Properties properties = new Properties();

		properties.put("drools.processInstanceManagerFactory", "org.jbpm.persistence.processinstance.JPAProcessInstanceManagerFactory");
		properties.put("drools.processSignalManagerFactory", "org.jbpm.persistence.processinstance.JPASignalManagerFactory");

		/*
		 * From ProcessFlowProvision.
		 */
		properties.put("drools.commandService", SingleSessionCommandService.class.getName());
		properties.put("drools.workItemManagerFactory", JPAWorkItemManagerFactory.class.getName());
		properties.setProperty("drools.timerService", JpaJDKTimerService.class.getName());

		return KnowledgeBaseFactory.newKnowledgeSessionConfiguration(properties);
	}

/**
	 * Registers the {@link WorkItemHand
	 * @param ksession
	 */
	private void registerWorkItemHandlers(final StatefulKnowledgeSession ksession, WorkItemHandlerBuilder wihBuilder) {
		WorkItemManager wiManager = ksession.getWorkItemManager();
		Map<String, WorkItemHandler> workItemHandlers = wihBuilder.buildWorkItemHandlers();
		Set<Map.Entry<String, WorkItemHandler>> workItemHandlersEntries = workItemHandlers.entrySet();
		for (Map.Entry<String, WorkItemHandler> nextWorkItemHandlerEntry: workItemHandlersEntries) {
			if (LOGGER.isDebugEnabled()) {
				String key = nextWorkItemHandlerEntry.getKey();
				WorkItemHandler wih = nextWorkItemHandlerEntry.getValue();
				LOGGER.debug("Registering WorkItemHandler of type '" + wih.getClass().getCanonicalName() + "' with name '" + key + "' in KnowledgeSession.");
				
			}
			wiManager.registerWorkItemHandler(nextWorkItemHandlerEntry.getKey(), nextWorkItemHandlerEntry.getValue());
		}
		
		/*
		 * Register our WorkItemHandlers.
		 * 
		 * TODO: This should be a lot more configurable and dynamic. A far better approach would to define some injection mechanism to
		 * inject the WorkItemHandlers to be registered with the KnowledgeSession's WorkItemManager.
		 */
		/*
		 * First register our LongRunningExternalProcessWorkItemHandler.
		 */
		// Dynamically load the registerd WorkItemHandlers.
		/*
		Class longRunningExternalProcessWIHClass;
		try {
			longRunningExternalProcessWIHClass = Class
					.forName("org.jboss.ddoyle.jbpm5.enterprise.demo.workitemhandlers.LongRunningExternalProcessWorkItemHandler");
		} catch (ClassNotFoundException cnfe) {
			throw new RuntimeException("Unable to instantiate LongRunningExternalProces WorkItemHandler.", cnfe);
		}
		WorkItemHandler longRunningExternalProcessWIH;
		try {
			longRunningExternalProcessWIH = (WorkItemHandler) longRunningExternalProcessWIHClass.newInstance();
		} catch (InstantiationException ie) {
			throw new RuntimeException("Unable to instantiate LongRunningExternalProces WorkItemHandler.", ie);
		} catch (IllegalAccessException iae) {
			throw new RuntimeException("Unable to instantiate LongRunningExternalProces WorkItemHandler.", iae);
		}

		// WorkItemHandler longRunningExternalProcessWIH = new LongRunningExternalProcessWorkItemHandler();
		ksession.getWorkItemManager().registerWorkItemHandler("LongRunningExternalProcess", longRunningExternalProcessWIH);
		*/
		/*
		 * Now the WorkItemManager for the EventFiringWIH which requires a ksession.
		 */
		// Dynamically load the registerd WorkItemHandlers.
		/*
		Class eventFiringWIHClass;
		try {
			eventFiringWIHClass = Class.forName("org.jboss.ddoyle.jbpm5.enterprise.demo.workitemhandlers.EventFiringWorkItemHandler");
		} catch (ClassNotFoundException cnfe) {
			throw new RuntimeException("Unable to instantiate Event Firing WorkItemHandler.", cnfe);
		}
		WorkItemHandler eventFiringWIH;
		try {
			// Use the constructor which accepts the StatefulKnowledgeSession as its argument.
			Constructor eventFiringWIHConstructor = (Constructor) eventFiringWIHClass.getConstructor(StatefulKnowledgeSession.class);
			eventFiringWIH = (WorkItemHandler) eventFiringWIHConstructor.newInstance(ksession);
			
		} catch (NoSuchMethodException nsme) {
			throw new RuntimeException("Unable to instantiate Event Firing WorkItemHandler.", nsme);
		} catch (SecurityException se) {
			throw new RuntimeException("Unable to instantiate Event Firing WorkItemHandler.", se);
		} catch (InstantiationException ie) {
			throw new RuntimeException("Unable to instantiate Event Firing WorkItemHandler.", ie);
		} catch (IllegalAccessException iae) {
			throw new RuntimeException("Unable to instantiate Event Firing WorkItemHandler.", iae);
		} catch (IllegalArgumentException iae) {
			throw new RuntimeException("Unable to instantiate Event Firing WorkItemHandler.", iae);
		} catch (InvocationTargetException ite) {
			throw new RuntimeException("Unable to instantiate Event Firing WorkItemHandler.", ite);
		}
	
		// WorkItemHandler longRunningExternalProcessWIH = new LongRunningExternalProcessWorkItemHandler();
		ksession.getWorkItemManager().registerWorkItemHandler("EventFiring", eventFiringWIH);
		*/
		/*
		 * HumanTask WorkItemHandler. Using HornetQ implementation. TODO: Implement Factory pattern to better abstract the Creation of this
		 * handler from this service class.
		 * 
		 * TODO: A good idea might be to create an AbstractFactoryPattern, which produces a factory for a specific jBPM mode (i.e.
		 * persistent, non-persistent), and is able to create a family of jBPM5 classes (task handler, knowlegde session configuration,
		 * etc.).
		 * 
		 * TODO: The current implementation of this CommandBasedWSHumanTaskHandler (especially its inner-class
		 * GetResultContentResponseHandler) re-uses the ksession we set here. This gives problems when we dispose the knowledgesession after
		 * the process instance has reached a wait-state (i.e. a Human Task wait-state), as the process can't continue on a disposed
		 * session.
		 * 
		 * A solution would be to implement a HumanTask response handler based on JMS and MDBs, which re-loads the StatefulKnowledgeSession
		 * (SKS) from the database using the JPAKnowledgeService. This way, we re-load the SKS when the process needs to continue, after
		 * which the process can continue in its own session.
		 * 
		 * Another strange thing about those WSHumanTaskHandlers is that they all start a new, unmanaged thread to handle the response.
		 * Apart from the fact that it is discouraged to start threads in a JEE environment, it also doesn't scale very well. Under heavy
		 * load, with a lot of human tasks, the system will create an insane amount of listener threads. Second, when a node crashes (or is
		 * just plain restarted), completion of Human Tasks will not trigger continuation of the process, because there are no threads
		 * listening for a response anymore. This definitely needs to be redesigned. We will base our design on the HornetW
		 * WSHumanTaskHandler and our own session management components.
		 */

		/*
		 * CommandBasedWSHumanTaskHandler humanTaskHandler = new CommandBasedWSHumanTaskHandler(ksession); TaskClient client = new
		 * TaskClient(new HornetQTaskClientConnector("client 1", new HornetQTaskClientHandler(
		 * SystemEventListenerFactory.getSystemEventListener()))); // Connect the wrapped HornetQ client. client.connect("127.0.0.1", 5446);
		 * // And configure it on the handler. humanTaskHandler.configureClient(client); // Add the handler to the knowledge session.
		 * ksession.getWorkItemManager().registerWorkItemHandler("Human Task", humanTaskHandler);
		 */
	}

	private void registerEventListener(final StatefulKnowledgeSession ksession) {
		/*
		 * Register our AgendaEventListener to enable RuleTask nodes. If we don't add this listener, the RuleTask will activate a
		 * rulflowgroup, but the rules won't be executed. See the JavaDoc of the LettergenAgendaEventListener for more information about
		 * these semantics.
		 */
		ksession.addEventListener(new jBPMAgendaEventListener());

	}

	public synchronized Environment getEnvironment() {
		Environment env = KnowledgeBaseFactory.newEnvironment();

		// Use the persistence-unit name as defined in the persistence.xml file.
		// We're using JTA transaction type, so we're not allowed to create the EntityManagerFactory ourselves.
		// We need to retrieve the EntityManager from JNDI
		// See: http://openejb.apache.org/jpa-concepts.html
		// EntityManagerFactory emf = Persistence.createEntityManagerFactory(PERSISTENCE_UNIT_NAME);
		if (jbpmCoreEMF == null) {
			try {
				jbpmCoreEMF = (EntityManagerFactory) ic.lookup("java:/jBPM5EntityManagerFactory");
			} catch (Exception e) {
				throw new IllegalStateException("JNDI lookup of entity manager factory failed.");
			}
		}

		LOGGER.info("Setting Entity Manager Factory");

		env.set(EnvironmentName.ENTITY_MANAGER_FACTORY, jbpmCoreEMF);
		// Also setting user transaction and transaction manager.
		env.set(EnvironmentName.TRANSACTION_MANAGER, transactionManager);
		env.set(EnvironmentName.TRANSACTION, emfUserTransaction);
		return env;
	}

	/**
	 * Gets the user transaction from JNDI.
	 * 
	 * @return
	 */
	public UserTransaction getUserTransaction() {
		UserTransaction ut = null;
		try {
			ut = (UserTransaction) ic.lookup("UserTransaction");
			if (ut == null)
				throw new IllegalStateException("JNDI lookup of user transaction failed.");
		} catch (Exception e) {
			e.printStackTrace();
			throw new IllegalStateException("JNDI lookup of user transaction failed.");
		}
		return ut;
	}

	/**
	 * Gets the transaction manager from JNDI.
	 * 
	 * @return
	 */
	private TransactionManager getTransactionManager() {
		TransactionManager tm = null;
		try {
			tm = (TransactionManager) ic.lookup("java:/TransactionManager");
			if (tm == null)
				throw new IllegalStateException("JNDI lookup of transaction manager failed.");
		} catch (Exception e) {
			e.printStackTrace();
			throw new IllegalStateException("JNDI lookup of transaction manager failed.");
		}
		return tm;
	}

	public StatefulKnowledgeSession getKSession() {
		return getKSession(null);
	}

	public StatefulKnowledgeSession loadKSession(int ksessionId) {
		return getKSession(ksessionId);
	}

	/**
	 * Knowledge session wrapper which basic purpose is to be able to discard any {@link KnowledgeRuntimeLogger} when we dispose the
	 * session.
	 */
	private class StatefulKnowledgeSessionWrapper implements StatefulKnowledgeSession {

		final StatefulKnowledgeSession ksession;
		final List<KnowledgeRuntimeLogger> krLoggers;
		final List<WorkingMemoryLogger> wmLoggers;

		public StatefulKnowledgeSessionWrapper(StatefulKnowledgeSession ksession) {
			this.ksession = ksession;
			// Copy the provided loggers to our list.
			this.krLoggers = new ArrayList<KnowledgeRuntimeLogger>();
			this.wmLoggers = new ArrayList<WorkingMemoryLogger>();

		}

		@Override
		public void dispose() {

			for (KnowledgeRuntimeLogger nextLogger : krLoggers) {
				nextLogger.close();
			}
			// The WorkingMemory loggers don't seem to have a close method, so we don't need to do anything with them.

			ksession.dispose();

			// TODO We could chose to implement some sort of Event that triggers the cleanup of the Sessions from the JPA tables every so
			// often.
		}

		public void addKnowledgeRuntimeLogger(KnowledgeRuntimeLogger logger) {
			krLoggers.add(logger);
		}

		public void addWorkingMemoryLogger(WorkingMemoryLogger logger) {
			wmLoggers.add(logger);
		}

		public StatefulKnowledgeSession getKSession() {
			return ksession;
		}

		@Override
		public int fireAllRules() {
			return ksession.fireAllRules();
		}

		@Override
		public int fireAllRules(int arg0) {
			return ksession.fireAllRules(arg0);
		}

		@Override
		public int fireAllRules(AgendaFilter arg0) {
			return ksession.fireAllRules(arg0);
		}

		@Override
		public int fireAllRules(AgendaFilter arg0, int arg1) {
			return ksession.fireAllRules(arg0, arg1);
		}

		@Override
		public void fireUntilHalt() {
			ksession.fireUntilHalt();
		}

		@Override
		public void fireUntilHalt(AgendaFilter arg0) {
			ksession.fireUntilHalt(arg0);
		}

		@Override
		public <T> T execute(Command<T> arg0) {
			return ksession.execute(arg0);
		}

		@Override
		public Calendars getCalendars() {
			return ksession.getCalendars();
		}

		@Override
		public Map<String, Channel> getChannels() {
			return ksession.getChannels();
		}

		@Override
		public Environment getEnvironment() {
			return ksession.getEnvironment();
		}

		@Override
		public Object getGlobal(String arg0) {
			return ksession.getGlobal(arg0);
		}

		@Override
		public Globals getGlobals() {
			return ksession.getGlobals();
		}

		@Override
		public KnowledgeBase getKnowledgeBase() {
			return ksession.getKnowledgeBase();
		}

		@Override
		public <T extends SessionClock> T getSessionClock() {
			return ksession.getSessionClock();
		}

		@Override
		public KnowledgeSessionConfiguration getSessionConfiguration() {
			return ksession.getSessionConfiguration();
		}

		@Override
		public void registerChannel(String arg0, Channel arg1) {
			ksession.registerChannel(arg0, arg1);
		}

		@Override
		public void registerExitPoint(String arg0, ExitPoint arg1) {
			ksession.registerExitPoint(arg0, arg1);
		}

		@Override
		public void setGlobal(String arg0, Object arg1) {
			ksession.setGlobal(arg0, arg1);
		}

		@Override
		public void unregisterChannel(String arg0) {
			ksession.unregisterChannel(arg0);
		}

		@Override
		public void unregisterExitPoint(String arg0) {
			ksession.unregisterExitPoint(arg0);
		}

		@Override
		public Agenda getAgenda() {
			return ksession.getAgenda();
		}

		@Override
		public QueryResults getQueryResults(String arg0, Object... arg1) {
			return ksession.getQueryResults(arg0, arg1);
		}

		@Override
		public WorkingMemoryEntryPoint getWorkingMemoryEntryPoint(String arg0) {
			return ksession.getWorkingMemoryEntryPoint(arg0);
		}

		@Override
		public Collection<? extends WorkingMemoryEntryPoint> getWorkingMemoryEntryPoints() {
			return ksession.getWorkingMemoryEntryPoints();
		}

		@Override
		public void halt() {
			ksession.halt();
		}

		@Override
		public LiveQuery openLiveQuery(String arg0, Object[] arg1, ViewChangedEventListener arg2) {
			return ksession.openLiveQuery(arg0, arg1, arg2);
		}

		@Override
		public String getEntryPointId() {
			return ksession.getEntryPointId();
		}

		@Override
		public long getFactCount() {
			return ksession.getFactCount();
		}

		@Override
		public FactHandle getFactHandle(Object arg0) {
			return ksession.getFactHandle(arg0);
		}

		@Override
		public <T extends FactHandle> Collection<T> getFactHandles() {
			return ksession.getFactHandles();
		}

		@Override
		public <T extends FactHandle> Collection<T> getFactHandles(ObjectFilter arg0) {
			return ksession.getFactHandles(arg0);
		}

		@Override
		public Object getObject(FactHandle arg0) {
			return ksession.getObject(arg0);
		}

		@Override
		public Collection<Object> getObjects() {
			return ksession.getObjects();
		}

		@Override
		public Collection<Object> getObjects(ObjectFilter arg0) {
			return ksession.getObjects(arg0);
		}

		@Override
		public FactHandle insert(Object arg0) {
			return ksession.insert(arg0);
		}

		@Override
		public void retract(FactHandle arg0) {
			ksession.retract(arg0);
		}

		@Override
		public void update(FactHandle arg0, Object arg1) {
			ksession.update(arg0, arg1);
		}

		@Override
		public void abortProcessInstance(long arg0) {
			ksession.abortProcessInstance(arg0);
		}

		@Override
		public ProcessInstance createProcessInstance(String arg0, Map<String, Object> arg1) {
			return ksession.createProcessInstance(arg0, arg1);
		}

		@Override
		public ProcessInstance getProcessInstance(long arg0) {
			return ksession.getProcessInstance(arg0);
		}

		@Override
		public Collection<ProcessInstance> getProcessInstances() {
			return ksession.getProcessInstances();
		}

		@Override
		public WorkItemManager getWorkItemManager() {
			return ksession.getWorkItemManager();
		}

		@Override
		public void signalEvent(String arg0, Object arg1) {
			ksession.signalEvent(arg0, arg1);
		}

		@Override
		public void signalEvent(String arg0, Object arg1, long arg2) {
			ksession.signalEvent(arg0, arg1, arg2);
		}

		@Override
		public ProcessInstance startProcess(String arg0) {
			return ksession.startProcess(arg0);
		}

		@Override
		public ProcessInstance startProcess(String arg0, Map<String, Object> arg1) {
			return ksession.startProcess(arg0, arg1);
		}

		@Override
		public ProcessInstance startProcessInstance(long arg0) {
			return ksession.startProcessInstance(arg0);
		}

		@Override
		public void addEventListener(WorkingMemoryEventListener arg0) {
			ksession.addEventListener(arg0);
		}

		@Override
		public void addEventListener(AgendaEventListener arg0) {
			ksession.addEventListener(arg0);
		}

		@Override
		public Collection<AgendaEventListener> getAgendaEventListeners() {
			return ksession.getAgendaEventListeners();
		}

		@Override
		public Collection<WorkingMemoryEventListener> getWorkingMemoryEventListeners() {
			return ksession.getWorkingMemoryEventListeners();
		}

		@Override
		public void removeEventListener(WorkingMemoryEventListener arg0) {
			ksession.removeEventListener(arg0);
		}

		@Override
		public void removeEventListener(AgendaEventListener arg0) {
			ksession.removeEventListener(arg0);
		}

		@Override
		public void addEventListener(ProcessEventListener arg0) {
			ksession.addEventListener(arg0);
		}

		@Override
		public Collection<ProcessEventListener> getProcessEventListeners() {
			return ksession.getProcessEventListeners();
		}

		@Override
		public void removeEventListener(ProcessEventListener arg0) {
			ksession.removeEventListener(arg0);

		}

		@Override
		public int getId() {
			return ksession.getId();
		}

	}

}
