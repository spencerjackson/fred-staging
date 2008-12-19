/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeSSK;
import freenet.node.BaseSendableGet;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.support.Logger;
import freenet.support.RandomGrabArray;
import freenet.support.SectoredRandomGrabArrayWithInt;
import freenet.support.SectoredRandomGrabArrayWithObject;
import freenet.support.SortedVectorByNumber;

/**
 * Base class for ClientRequestSchedulerCore and ClientRequestSchedulerNonPersistent, 
 * contains some of the methods and most of the variables. In particular, it contains all 
 * the methods that deal primarily with pendingKeys.
 * @author toad
 */
abstract class ClientRequestSchedulerBase {
	
	/** Minimum number of retries at which we start to hold it against a request.
	 * See the comments on fixRetryCount; we don't want many untried requests to prevent
	 * us from trying requests which have only been tried once (e.g. USK checkers), from 
	 * other clients (and we DO want retries to take precedence over client round robin IF 
	 * the request has been tried many times already). */
	private static final int MIN_RETRY_COUNT = 3;

	private static boolean logMINOR;
	
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	
	/**
	 * Structure:
	 * array (by priority) -> // one element per possible priority
	 * SortedVectorByNumber (by # retries) -> // contains each current #retries
	 * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
	 * 
	 * To speed up fetching, a RGA or SVBN must only exist if it is non-empty.
	 */
	protected final SortedVectorByNumber[] priorities;
	protected transient ClientRequestScheduler sched;
	/** Transient even for persistent scheduler. */
	protected transient Set<KeyListener> keyListeners;

	abstract boolean persistent();
	
	protected ClientRequestSchedulerBase(boolean forInserts, boolean forSSKs) {
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		keyListeners = new HashSet<KeyListener>();
		priorities = new SortedVectorByNumber[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
		logMINOR = Logger.shouldLog(Logger.MINOR, ClientRequestSchedulerBase.class);
	}
	
	/**
	 * @param req
	 * @param random
	 * @param container
	 * @param maybeActive Array of requests, can be null, which are being registered
	 * in this group. These will be ignored for purposes of checking whether stuff
	 * is activated when it shouldn't be. It is perfectly okay to have req be a
	 * member of maybeActive.
	 * 
	 * FIXME: Either get rid of the debugging code and therefore get rid of maybeActive,
	 * or make req a SendableRequest[] and register them all at once.
	 */
	void innerRegister(SendableRequest req, RandomSource random, ObjectContainer container, SendableRequest[] maybeActive) {
		if(isInsertScheduler && req instanceof BaseSendableGet)
			throw new IllegalArgumentException("Adding a SendableGet to an insert scheduler!!");
		if((!isInsertScheduler) && req instanceof SendableInsert)
			throw new IllegalArgumentException("Adding a SendableInsert to a request scheduler!!");
		if(isInsertScheduler != req.isInsert())
			throw new IllegalArgumentException("Request isInsert="+req.isInsert()+" but my isInsertScheduler="+isInsertScheduler+"!!");
		if(req.persistent() != persistent())
			throw new IllegalArgumentException("innerRegister for persistence="+req.persistent()+" but our persistence is "+persistent());
		if(req.getPriorityClass(container) == 0) {
			Logger.normal(this, "Something wierd...");
			Logger.normal(this, "Priority "+req.getPriorityClass(container));
		}
		int retryCount = req.getRetryCount();
		short prio = req.getPriorityClass(container);
		if(logMINOR) Logger.minor(this, "Still registering "+req+" at prio "+prio+" retry "+retryCount+" for "+req.getClientRequest());
		addToRequestsByClientRequest(req.getClientRequest(), req, container);
		addToGrabArray(prio, retryCount, fixRetryCount(retryCount), req.getClient(), req.getClientRequest(), req, random, container);
		if(logMINOR) Logger.minor(this, "Registered "+req+" on prioclass="+prio+", retrycount="+retryCount);
		if(persistent())
			sched.maybeAddToStarterQueue(req, container, maybeActive);
	}
	
	protected void addToRequestsByClientRequest(ClientRequester clientRequest, SendableRequest req, ObjectContainer container) {
		if(clientRequest != null || persistent()) // Client request null is only legal for transient requests
			clientRequest.addToRequests(req, container);
	}
	
	synchronized void addToGrabArray(short priorityClass, int retryCount, int rc, RequestClient client, ClientRequester cr, SendableRequest req, RandomSource random, ObjectContainer container) {
		if((priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS) || (priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS))
			throw new IllegalStateException("Invalid priority: "+priorityClass+" - range is "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" (most important) to "+RequestStarter.MINIMUM_PRIORITY_CLASS+" (least important)");
		// Priority
		SortedVectorByNumber prio = priorities[priorityClass];
		if(prio == null) {
			prio = new SortedVectorByNumber(persistent());
			priorities[priorityClass] = prio;
			if(persistent())
				container.store(this);
		}
		// Client
		SectoredRandomGrabArrayWithInt clientGrabber = (SectoredRandomGrabArrayWithInt) prio.get(rc, container);
		if(persistent()) container.activate(clientGrabber, 1);
		if(clientGrabber == null) {
			clientGrabber = new SectoredRandomGrabArrayWithInt(rc, persistent(), container);
			prio.add(clientGrabber, container);
			if(logMINOR) Logger.minor(this, "Registering retry count "+rc+" with prioclass "+priorityClass+" on "+clientGrabber+" for "+prio);
		}
		// SectoredRandomGrabArrayWithInt and lower down have hierarchical locking and auto-remove.
		// To avoid a race condition it is essential to mirror that here.
		synchronized(clientGrabber) {
			// Request
			SectoredRandomGrabArrayWithObject requestGrabber = (SectoredRandomGrabArrayWithObject) clientGrabber.getGrabber(client);
			if(persistent()) container.activate(requestGrabber, 1);
			if(requestGrabber == null) {
				requestGrabber = new SectoredRandomGrabArrayWithObject(client, persistent(), container);
				if(logMINOR)
					Logger.minor(this, "Creating new grabber: "+requestGrabber+" for "+client+" from "+clientGrabber+" : "+prio+" : prio="+priorityClass+", rc="+rc);
				clientGrabber.addGrabber(client, requestGrabber, container);
			}
			requestGrabber.add(cr, req, container);
		}
	}

	/**
	 * Mangle the retry count.
	 * Below a certain number of attempts, we don't prefer one request to another just because
	 * it's been tried more times. The reason for this is to prevent floods of low-retry-count
	 * requests from starving other clients' requests which need to be retried. The other
	 * solution would be to sort by client before retry count, but that would be excessive 
	 * IMHO; we DO want to avoid rerequesting keys we've tried many times before.
	 */
	protected int fixRetryCount(int retryCount) {
		return Math.max(0, retryCount-MIN_RETRY_COUNT);
	}

	/**
	 * Get SendableRequest's for a given ClientRequester.
	 * Note that this will return all kinds of requests, so the caller will have
	 * to filter them according to isInsert and isSSKScheduler.
	 */
	protected SendableRequest[] getSendableRequests(ClientRequester request, ObjectContainer container) {
		if(request != null || persistent()) // Client request null is only legal for transient requests
			return request.getSendableRequests(container);
		else return null;
	}

	void removeFromAllRequestsByClientRequest(SendableRequest req, ClientRequester cr, boolean dontComplain, ObjectContainer container) {
		if(cr != null || persistent()) // Client request null is only legal for transient requests
			cr.removeFromRequests(req, container, dontComplain);
	}
	
	public void reregisterAll(ClientRequester request, RandomSource random, RequestScheduler lock, ObjectContainer container, ClientContext context) {
		if(request.persistent() != persistent()) return;
		SendableRequest[] reqs = getSendableRequests(request, container);
		
		if(reqs == null) return;
		for(int i=0;i<reqs.length;i++) {
			SendableRequest req = reqs[i];
			if(persistent())
				container.activate(req, 1);
			// FIXME call getSendableRequests() and do the sorting in ClientRequestScheduler.reregisterAll().
			if(req.isInsert() != isInsertScheduler || req.isSSK() != isSSKScheduler) {
				container.deactivate(req, 1);
				continue;
			}
			// Unregister from the RGA's, but keep the pendingKeys and cooldown queue data.
			req.unregister(container, context);
			// Then can do innerRegister() (not register()).
			innerRegister(req, random, container, null);
			if(persistent())
				container.deactivate(req, 1);
		}
	}

	public void succeeded(BaseSendableGet succeeded, ObjectContainer container) {
		// Do nothing.
		// FIXME: Keep a list of recently succeeded ClientRequester's.
	}

	public synchronized void addPendingKeys(KeyListener listener) {
		if(listener == null) throw new NullPointerException();
		keyListeners.add(listener);
		Logger.normal(this, "Added pending keys to "+this+" : size now "+keyListeners.size()+" : "+listener);
	}
	
	public synchronized boolean removePendingKeys(KeyListener listener) {
		boolean ret = keyListeners.remove(listener);
		listener.onRemove();
		Logger.normal(this, "Removed pending keys from "+this+" : size now "+keyListeners.size()+" : "+listener);
		return ret;
	}
	
	public synchronized boolean removePendingKeys(HasKeyListener hasListener) {
		boolean found = false;
		for(Iterator<KeyListener> i = keyListeners.iterator();i.hasNext();) {
			KeyListener listener = i.next();
			if(listener == null) {
				i.remove();
				Logger.error(this, "Null KeyListener in removePendingKeys()");
				continue;
			}
			if(listener.getHasKeyListener() == hasListener) {
				found = true;
				i.remove();
				listener.onRemove();
				Logger.normal(this, "Removed pending keys from "+this+" : size now "+keyListeners.size()+" : "+listener);
			}
		}
		return found;
	}
	
	public short getKeyPrio(Key key, short priority, ObjectContainer container, ClientContext context) {
		byte[] saltedKey = ((key instanceof NodeSSK) ? context.getSskFetchScheduler() : context.getChkFetchScheduler()).saltKey(key);
		ArrayList<KeyListener> matches = null;
		synchronized(this) {
			for(KeyListener listener : keyListeners) {
				if(!listener.probablyWantKey(key, saltedKey)) continue;
				if(matches == null) matches = new ArrayList<KeyListener> ();
				matches.add(listener);
			}
		}
		if(matches == null) return priority;
		for(KeyListener listener : matches) {
			short prio = listener.definitelyWantKey(key, saltedKey, container, sched.clientContext);
			if(prio == -1) continue;
			if(prio < priority) priority = prio;
		}
		return priority;
	}
	
	public synchronized long countWaitingKeys(ObjectContainer container) {
		long count = 0;
		for(KeyListener listener : keyListeners)
			count += listener.countKeys();
		return count;
	}
	
	public boolean anyWantKey(Key key, ObjectContainer container, ClientContext context) {
		byte[] saltedKey = ((key instanceof NodeSSK) ? context.getSskFetchScheduler() : context.getChkFetchScheduler()).saltKey(key);
		ArrayList<KeyListener> matches = null;
		synchronized(this) {
			for(KeyListener listener : keyListeners) {
				if(!listener.probablyWantKey(key, saltedKey)) continue;
				if(matches == null) matches = new ArrayList<KeyListener> ();
				matches.add(listener);
			}
		}
		if(matches != null) {
			for(KeyListener listener : matches) {
				if(listener.definitelyWantKey(key, saltedKey, container, sched.clientContext) >= 0)
					return true;
			}
		}
		return false;
	}
	
	public synchronized boolean anyProbablyWantKey(Key key, ClientContext context) {
		byte[] saltedKey = ((key instanceof NodeSSK) ? context.getSskFetchScheduler() : context.getChkFetchScheduler()).saltKey(key);
		for(KeyListener listener : keyListeners) {
			if(listener.probablyWantKey(key, saltedKey))
				return true;
		}
		return false;
	}
	
	private long persistentTruePositives;
	private long persistentFalsePositives;
	private long persistentNegatives;
	
	public boolean tripPendingKey(Key key, KeyBlock block, ObjectContainer container, ClientContext context) {
		byte[] saltedKey = ((key instanceof NodeSSK) ? context.getSskFetchScheduler() : context.getChkFetchScheduler()).saltKey(key);
		ArrayList<KeyListener> matches = null;
		synchronized(this) {
			for(KeyListener listener : keyListeners) {
				if(!listener.probablyWantKey(key, saltedKey)) continue;
				if(matches == null) matches = new ArrayList<KeyListener> ();
				matches.add(listener);
			}
		}
		boolean ret = false;
		if(matches != null) {
			for(KeyListener listener : matches) {
				if(listener.handleBlock(key, saltedKey, block, container, context))
					ret = true;
				if(listener.isEmpty()) {
					synchronized(this) {
						keyListeners.remove(listener);
					}
					listener.onRemove();
				}
			}
		} else return false;
		if(ret) {
			// True positive
			synchronized(this) {
				persistentTruePositives++;
				logFalsePositives("hit");
			}
		} else {
			synchronized(this) {
				persistentFalsePositives++;
				logFalsePositives("false");
			}
		}
		return ret;
	}
	
	synchronized void countNegative() {
		persistentNegatives++;
		if(persistentNegatives % 32 == 0)
			logFalsePositives("neg");
	}
	
	private synchronized void logFalsePositives(String phase) {
		long totalPositives = persistentFalsePositives + persistentTruePositives;
		double percent;
		if(totalPositives > 0)
			percent = ((double) persistentFalsePositives) / totalPositives;
		else
			percent = 0;
		if(!(percent > 2 || logMINOR)) return;
		StringBuilder buf = new StringBuilder();
		if(persistent())
			buf.append("Persistent ");
		else
			buf.append("Transient ");
		buf.append("false positives ");
		buf.append(phase);
		buf.append(": ");
		
		if(totalPositives != 0) {
			buf.append(percent);
			buf.append("% ");
		}
		buf.append("(false=");
		buf.append(persistentFalsePositives);
		buf.append(" true=");
		buf.append(persistentTruePositives);
		buf.append(" negatives=");
		buf.append(persistentNegatives);
		buf.append(')');
		if(percent > 10)
			Logger.error(this, buf.toString());
		else if(percent > 2)
			Logger.normal(this, buf.toString());
		else
			Logger.minor(this, buf.toString());
	}

	public SendableGet[] requestsForKey(Key key, ObjectContainer container, ClientContext context) {
		ArrayList<SendableGet> list = null;
		byte[] saltedKey = ((key instanceof NodeSSK) ? context.getSskFetchScheduler() : context.getChkFetchScheduler()).saltKey(key);
		synchronized(this) {
		for(KeyListener listener : keyListeners) {
			if(!listener.probablyWantKey(key, saltedKey)) continue;
			SendableGet[] reqs = listener.getRequestsForKey(key, saltedKey, container, context);
			if(reqs == null) continue;
			if(list == null) list = new ArrayList<SendableGet>();
			for(int i=0;i<reqs.length;i++) list.add(reqs[i]);
		}
		}
		if(list == null) return null;
		else return list.toArray(new SendableGet[list.size()]);
	}
	
	public void onStarted() {
		keyListeners = new HashSet<KeyListener>();
	}
	
	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append(super.toString());
		sb.append(':');
		if(isInsertScheduler)
			sb.append("insert:");
		if(isSSKScheduler)
			sb.append("SSK");
		else
			sb.append("CHK");
		return sb.toString();
	}

	public synchronized long countQueuedRequests(ObjectContainer container) {
		long total = 0;
		for(int i=0;i<priorities.length;i++) {
			SortedVectorByNumber prio = priorities[i];
			if(prio == null || prio.isEmpty())
				System.out.println("Priority "+i+" : empty");
			else {
				System.out.println("Priority "+i+" : "+prio.count());
				for(int j=0;j<prio.count();j++) {
					int frc = prio.getNumberByIndex(j);
					System.out.println("Fixed retry count: "+frc);
					SectoredRandomGrabArrayWithInt clientGrabber = (SectoredRandomGrabArrayWithInt) prio.get(frc, container);
					container.activate(clientGrabber, 1);
					System.out.println("Clients: "+clientGrabber.size()+" for "+clientGrabber);
					for(int k=0;k<clientGrabber.size();k++) {
						Object client = clientGrabber.getClient(k);
						container.activate(client, 1);
						System.out.println("Client "+k+" : "+client);
						container.deactivate(client, 1);
						SectoredRandomGrabArrayWithObject requestGrabber = (SectoredRandomGrabArrayWithObject) clientGrabber.getGrabber(client);
						container.activate(requestGrabber, 1);
						System.out.println("SRGA for client: "+requestGrabber);
						for(int l=0;l<requestGrabber.size();l++) {
							client = requestGrabber.getClient(l);
							container.activate(client, 1);
							System.out.println("Request "+l+" : "+client);
							container.deactivate(client, 1);
							RandomGrabArray rga = (RandomGrabArray) requestGrabber.getGrabber(client);
							container.activate(rga, 1);
							System.out.println("Queued SendableRequests: "+rga.size()+" on "+rga);
							long sendable = 0;
							long all = 0;
							for(int m=0;m<rga.size();m++) {
								SendableRequest req = (SendableRequest) rga.get(m, container);
								if(req == null) continue;
								container.activate(req, 1);
								sendable += req.sendableKeys(container).length;
								all += req.allKeys(container).length;
								container.deactivate(req, 1);
							}
							System.out.println("Sendable keys: "+sendable+" all keys "+all+" diff "+(all-sendable));
							total += all;
							container.deactivate(rga, 1);
						}
						container.deactivate(requestGrabber, 1);
					}
					container.deactivate(clientGrabber, 1);
				}
			}
		}
		return total;
	}	
}