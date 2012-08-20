package org.jbpm.ee;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.transaction.InvalidTransactionException;
import javax.transaction.RollbackException;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.apache.log4j.Logger;
import org.drools.command.Command;
import org.drools.command.Context;
import org.drools.command.impl.GenericCommand;
import org.drools.command.impl.KnowledgeCommandContext;
import org.drools.runtime.StatefulKnowledgeSession;

/**
 * Drools command which registers a <code>JTA</code> {@link Transaction} {@link Synchronization} object on the current running
 * {@link Transaction} which will dispose the current {@link StatefulKnowledgeSession}.
 * <p/>
 * 
 * This {@link Command} can be used when one needs to call <code>dispose</code> on a {@link StatefulKnowledgeSession} from within a running
 * <code>JTA</code> {@link Transaction}.
 * 
 * 
 * @author <a href='mailto:mswiders@redhat.com>Maciej Swiderski</a>
 * @author <a href='mailto:duncan.doyle@redhat.com>Duncan Doyle</a>
 */
public class CMTDisposeCommand implements GenericCommand<Void> {

	private static final Logger LOGGER = Logger.getLogger(CMTDisposeCommand.class);

	private static final long serialVersionUID = 1L;

	private String tmLookupName = "java:/TransactionManager";

	public CMTDisposeCommand() {

	}

	public CMTDisposeCommand(String tmLookup) {
		this.tmLookupName = tmLookup;
	}

	@Override
	public Void execute(Context context) {

		final StatefulKnowledgeSession ksession = ((KnowledgeCommandContext) context).getStatefulKnowledgesession();
		try {
			final TransactionManager tm = (TransactionManager) new InitialContext().lookup(tmLookupName);
			tm.getTransaction().registerSynchronization(new Synchronization() {

				@Override
				public void beforeCompletion() {
					// not used here

				}

				@Override
				public void afterCompletion(int arg0) {
					try {
						ksession.dispose();
					} catch (IllegalStateException ise) {
						LOGGER.error("Error while disposing StatefulKnowledgeSession.", ise);
					}
				}
			});
		} catch (NamingException ne) {
			LOGGER.error("Error while registering Transaction Synchronization object for StatefulKnowledgeSession disposal.", ne);
		} catch (IllegalStateException ise) {
			LOGGER.error("Error while registering Transaction Synchronization object for StatefulKnowledgeSession disposal.", ise);
		} catch (RollbackException re) {
			LOGGER.error("Error while registering Transaction Synchronization object for StatefulKnowledgeSession disposal.", re);
		} catch (SystemException se) {
			LOGGER.error("Error while registering Transaction Synchronization object for StatefulKnowledgeSession disposal.", se);
		}
		return null;
	}

}
