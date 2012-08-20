package org.jboss.ddoyle.jbpm5.enterprise.demo.web.servlet;

import java.io.IOException;
import java.io.Writer;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jboss.ddoyle.jbpm5.enterprise.demo.client.messaging.SendJMSMessage;


/**
 * Servlet implementation class StartProcessServlet
 */
public class StartProcessServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
       
    /**
     * @see HttpServlet#HttpServlet()
     */
    public StartProcessServlet() {
        super();
    }

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		Writer responseWriter = response.getWriter();
		responseWriter.write("Starting business process. Firing JMS Message.\n");
		try {
			SendJMSMessage.main(null);
		} catch (Exception e) {
			responseWriter.write("Unable to send JMS Message. Unable to start process.");
		}
		responseWriter.flush();
		responseWriter.close();
	}

}
