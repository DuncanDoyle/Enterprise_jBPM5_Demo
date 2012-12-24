package demo.web.servlet;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;
import javax.transaction.UserTransaction;

import org.jboss.ddoyle.jbpm5.core.services.KSessionService;
import org.jboss.ddoyle.jbpm5.core.services.ProcessService;
import org.jboss.ddoyle.jbpm5.core.services.SimpleProcessService;

/**
 * Servlet implementation class StartProcessServlet
 */
public class DummyServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	/**
	 * Logger.
	 */
	// private static final Logger LOGGER = Logger.getLogger(DummyServlet.class);

	private InitialContext ic;

	private UserTransaction utx;

	private TransactionManager tm;

	{
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
	}

	/**
	 * @see HttpServlet#HttpServlet()
	 */
	public DummyServlet() {
		super();
	}

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		// Manually manage user transaction
		try {
			utx.begin();
		} catch (NotSupportedException nse) {
			throw new RuntimeException(nse);
		} catch (SystemException se) {
			throw new RuntimeException(se);
		}

		Writer responseWriter = null;
		try {
			responseWriter = response.getWriter();
			responseWriter.write("Starting business process. Firing JMS Message.\n");

			Map dataMap = new HashMap<String, Object>();
			Map returnMap = SimpleProcessService.getInstance().startProcess("defaultPackage.hello-world", dataMap);

			int ksessionId = (Integer) returnMap.get(KSessionService.KSESSION_ID);
			long processInstanceId = (Long) returnMap.get(ProcessService.PROCESS_INSTANCE_ID);
			String processInstanceUUID = (String) returnMap.get(SimpleProcessService.PROCESS_INSTANCE_UUID_VARIABLE_NAME);
			System.out.println("Started process instance of process: 'org.jboss.ddoyle.poc.SimplePocProcess' with process-id: '"
					+ processInstanceId + "' in knowledge-session: '" + ksessionId + "'. \n");
			/*
			 * LOGGER.info("Started process instance of process: 'org.jboss.ddoyle.poc.SimplePocProcess' with process-id: '" +
			 * processInstanceId + "' in knowledge-session: '" + ksessionId +"'. \n");
			 */

			responseWriter.flush();
			responseWriter.close();
			//Commit the transaction.
			try {
				utx.commit();
			} catch (SecurityException se) {
				throw new RuntimeException(se);
			} catch (IllegalStateException ise) {
				throw new RuntimeException(ise);
			} catch (RollbackException re) {
				throw new RuntimeException(re);
			} catch (HeuristicMixedException hme) {
				throw new RuntimeException(hme);
			} catch (HeuristicRollbackException hre) {
				throw new RuntimeException(hre);
			} catch (SystemException se) {
				throw new RuntimeException(se);
			}
		} catch (Exception e) {
			//Rollback the transaction on exceptions
			try {
				utx.setRollbackOnly();
			} catch (IllegalStateException ise) {
				throw new RuntimeException(ise);
			} catch (SystemException se) {
				throw new RuntimeException(se);
			}
			e.printStackTrace();
			if (responseWriter != null) {
				responseWriter.write("Unable to send JMS Message. Unable to start process.");
			}
		}
		
	}

}