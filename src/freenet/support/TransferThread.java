package freenet.support;

import java.util.Collection;
import java.util.Iterator;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.io.TempBucketFactory;

public abstract class TransferThread implements Runnable {
	
	private final String mName;
	protected final Node mNode;
	protected final HighLevelSimpleClient mClient;
	protected final TempBucketFactory mTBF;
	
	private Thread mThread;
	
	private volatile boolean isRunning = false;
	
	private final Collection<ClientGetter> mFetches = createFetchStorage();
	private final Collection<BaseClientPutter> mInserts = createInsertStorage();
	
	public TransferThread(Node myNode, HighLevelSimpleClient myClient, String myName) {
		mNode = myNode;
		mClient = myClient;
		mTBF = mNode.clientCore.tempBucketFactory;
		mName = myName;
	}
	
	protected void start() {
		mNode.executor.execute(this, mName);
	}

	public void run() {
		isRunning = true;
		mThread = Thread.currentThread();
		
		try {
			Thread.sleep(getStartupDelay());
		} catch (InterruptedException e) {
			mThread.interrupt();
		}
		
		while(isRunning) {
			Thread.interrupted();
			
			try {
				iterate();
				Thread.sleep(getSleepTime());
			}
			catch(InterruptedException e) {
				mThread.interrupt();
			}
		}
		
		abortAllTransfers();
	}
	
	protected void abortAllTransfers() {
		Logger.debug(this, "Trying to stop all requests & inserts");
		
		if(mFetches != null)
			synchronized(mFetches) {
				Iterator<ClientGetter> r = mFetches.iterator();
				int rcounter = 0;
				while (r.hasNext()) { r.next().cancel(); r.remove(); ++rcounter; }
				Logger.debug(this, "Stopped " + rcounter + " current requests");
			}

		if(mInserts != null)
			synchronized(mInserts) {
				Iterator<BaseClientPutter> i = mInserts.iterator();
				int icounter = 0;
				while (i.hasNext()) { i.next().cancel(); i.remove(); ++icounter; }
				Logger.debug(this, "Stopped " + icounter + " current inserts");
			}
	}
	
	protected void removeFetch(ClientGetter g) {
		synchronized(mFetches) {
			//g.cancel(); /* FIXME: is this necessary ? */
			mFetches.remove(g);
		}
		Logger.debug(this, "Removed request for " + g.getURI());
	}
	
	protected void removeInsert(BaseClientPutter p) {
		synchronized(mInserts) {
			//p.cancel(); /* FIXME: is this necessary ? */
			mInserts.remove(p);
		}
		Logger.debug(this, "Removed insert for " + p.getURI());
	}
	
	public void terminate() {
		isRunning = false;
		mThread.interrupt();
		try {
			mThread.join();
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
	
	
	protected abstract Collection<ClientGetter> createFetchStorage();
	
	protected abstract Collection<BaseClientPutter> createInsertStorage();
	
	protected abstract long getStartupDelay();

	protected abstract long getSleepTime();
	
	protected abstract void iterate();

	
	/* Fetches */
	
	public abstract void onSuccess(FetchResult result, ClientGetter state);

	public abstract void onFailure(FetchException e, ClientGetter state);

	/* Inserts */
	
	public abstract void onSuccess(BaseClientPutter state);

	public abstract void onFailure(InsertException e, BaseClientPutter state);

	public abstract void onFetchable(BaseClientPutter state);

	public abstract void onGeneratedURI(FreenetURI uri, BaseClientPutter state);

	/** Called when freenet.async thinks that the request should be serialized to
	 * disk, if it is a persistent request. */
	public abstract void onMajorProgress();

}