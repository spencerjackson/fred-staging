package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.events.FinishedCompressionEvent;
import freenet.client.events.StartedCompressionEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.CHKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.SSKBlock;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;
import freenet.support.io.NotPersistentBucket;
import freenet.support.io.SegmentedBucketChainBucket;

/**
 * Attempt to insert a file. May include metadata.
 * 
 * This stage:
 * Attempt to compress the file. Off-thread if it will take a while.
 * Then hand it off to SimpleFileInserter.
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
class SingleFileInserter implements ClientPutState {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
				logDEBUG = Logger.shouldLog(Logger.MINOR, this);
			}
		});
	}
	
	final BaseClientPutter parent;
	InsertBlock block;
	final InsertContext ctx;
	final boolean metadata;
	final PutCompletionCallback cb;
	final boolean getCHKOnly;
	final ARCHIVE_TYPE archiveType;
	/** If true, we are not the top level request, and should not
	 * update our parent to point to us as current put-stage. */
	private final boolean reportMetadataOnly;
	public final Object token;
	private final boolean freeData; // this is being set, but never read ???
	private final String targetFilename;
	private final boolean earlyEncode;
	private final boolean persistent;
	private boolean started;
	private boolean cancelled;
	private final boolean forSplitfile;
	
	// A persistent hashCode is helpful in debugging, and also means we can put
	// these objects into sets etc when we need to.
	
	private final int hashCode;
	
	@Override
	public int hashCode() {
		return hashCode;
	}

	/**
	 * @param parent
	 * @param cb
	 * @param block
	 * @param metadata
	 * @param ctx
	 * @param dontCompress
	 * @param getCHKOnly
	 * @param reportMetadataOnly If true, don't insert the metadata, just report it.
	 * @param insertAsArchiveManifest If true, insert the metadata as an archive manifest.
	 * @param freeData If true, free the data when possible.
	 * @param targetFilename 
	 * @param earlyEncode If true, try to get a URI as quickly as possible.
	 * @throws InsertException
	 */
	SingleFileInserter(BaseClientPutter parent, PutCompletionCallback cb, InsertBlock block, 
			boolean metadata, InsertContext ctx, boolean dontCompress, 
			boolean getCHKOnly, boolean reportMetadataOnly, Object token, ARCHIVE_TYPE archiveType,
			boolean freeData, String targetFilename, boolean earlyEncode, boolean forSplitfile, boolean persistent) {
		hashCode = super.hashCode();
		this.earlyEncode = earlyEncode;
		this.reportMetadataOnly = reportMetadataOnly;
		this.token = token;
		this.parent = parent;
		this.block = block;
		this.ctx = ctx;
		this.metadata = metadata;
		this.cb = cb;
		this.getCHKOnly = getCHKOnly;
		this.archiveType = archiveType;
		this.freeData = freeData;
		this.targetFilename = targetFilename;
		this.persistent = persistent;
		this.forSplitfile = forSplitfile;
		if(logMINOR) Logger.minor(this, "Created "+this+" persistent="+persistent+" freeData="+freeData);
	}
	
	public void start(SimpleFieldSet fs, ObjectContainer container, ClientContext context) throws InsertException {
		if(fs != null) {
			String type = fs.get("Type");
			if(type.equals("SplitHandler")) {
				// Try to reconstruct SplitHandler.
				// If we succeed, we bypass both compression and FEC encoding!
				try {
					SplitHandler sh = new SplitHandler();
					sh.start(fs, false, container, context);
					boolean wasActive = true;
					
					if(persistent) {
						wasActive = container.ext().isActive(cb);
						if(!wasActive)
							container.activate(cb, 1);
					}
					cb.onTransition(this, sh, container);
					sh.schedule(container, context);
					if(!wasActive)
						container.deactivate(cb, 1);
					return;
				} catch (ResumeException e) {
					Logger.error(this, "Failed to restore: "+e, e);
				}
			}
		}
		if(persistent) {
			container.activate(block, 1); // will cascade
		}
		tryCompress(container, context);
	}

	void onCompressed(CompressionOutput output, ObjectContainer container, ClientContext context) {
		boolean cbActive = true;
		if(persistent) {
			cbActive = container.ext().isActive(cb);
			if(!cbActive)
				container.activate(cb, 1);
		}
		if(started) {
			Logger.error(this, "Already started, not starting again", new Exception("error"));
			return;
		}
		if(cancelled) {
			Logger.error(this, "Already cancelled, not starting");
			return;
		}
		if(persistent) container.activate(block, 1);
		try {
			onCompressedInner(output, container, context);
		} catch (InsertException e) {
			cb.onFailure(e, SingleFileInserter.this, container, context);
        } catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
			System.err.println("OffThreadCompressor thread above failed.");
			// Might not be heap, so try anyway
			cb.onFailure(new InsertException(InsertException.INTERNAL_ERROR, e, null), SingleFileInserter.this, container, context);
        } catch (Throwable t) {
            Logger.error(this, "Caught in OffThreadCompressor: "+t, t);
            System.err.println("Caught in OffThreadCompressor: "+t);
            t.printStackTrace();
            // Try to fail gracefully
			cb.onFailure(new InsertException(InsertException.INTERNAL_ERROR, t, null), SingleFileInserter.this, container, context);
		}
		if(!cbActive)
			container.deactivate(cb, 1);
	}
	
	void onCompressedInner(CompressionOutput output, ObjectContainer container, ClientContext context) throws InsertException {
		boolean parentWasActive = true;
		if(container != null) {
			container.activate(block, 2);
			parentWasActive = container.ext().isActive(parent);
			if(!parentWasActive)
				container.activate(parent, 1);
		}
		long origSize = block.getData().size();
		Bucket bestCompressedData = output.data;
		long bestCompressedDataSize = bestCompressedData.size();
		Bucket data = bestCompressedData;
		COMPRESSOR_TYPE bestCodec = output.bestCodec;
		
		boolean shouldFreeData = freeData;
		if(bestCodec != null) {
			if(logMINOR) Logger.minor(this, "The best compression algorithm is "+bestCodec+ " we have gained"+ (100-(bestCompressedDataSize*100/origSize)) +"% ! ("+origSize+'/'+bestCompressedDataSize+')');
			shouldFreeData = true; // must be freed regardless of whether the original data was to be freed
			if(freeData) {
				block.getData().free();
				if(persistent) block.getData().removeFrom(container);
			}
			block.nullData();
			if(persistent) container.store(block);
		} else {
			data = block.getData();
		}

		int blockSize;
		int oneBlockCompressedSize;
		
		boolean isCHK = false;
		if(persistent) container.activate(block.desiredURI, 5);
		String type = block.desiredURI.getKeyType();
		boolean isUSK = false;
		if(type.equals("SSK") || type.equals("KSK") || (isUSK = type.equals("USK"))) {
			blockSize = SSKBlock.DATA_LENGTH;
			oneBlockCompressedSize = SSKBlock.MAX_COMPRESSED_DATA_LENGTH;
		} else if(type.equals("CHK")) {
			blockSize = CHKBlock.DATA_LENGTH;
			oneBlockCompressedSize = CHKBlock.MAX_COMPRESSED_DATA_LENGTH;
			isCHK = true;
		} else {
			throw new InsertException(InsertException.INVALID_URI, "Unknown key type: "+type, null);
		}
		
		// Compressed data ; now insert it
		// We do NOT need to switch threads here: the actual compression is done by InsertCompressor on the RealCompressor thread,
		// which then switches either to the database thread or to a new executable to run this method.
		
		if(parent == cb) {
			if(persistent) {
				container.activate(ctx, 1);
				container.activate(ctx.eventProducer, 1);
			}
			ctx.eventProducer.produceEvent(new FinishedCompressionEvent(bestCodec == null ? -1 : bestCodec.metadataID, origSize, data.size()), container, context);
			if(logMINOR) Logger.minor(this, "Compressed "+origSize+" to "+data.size()+" on "+this+" data = "+data);
		}
		
		// Insert it...
		short codecNumber = bestCodec == null ? -1 : bestCodec.metadataID;
		long compressedDataSize = data.size();
		boolean fitsInOneBlockAsIs = bestCodec == null ? compressedDataSize < blockSize : compressedDataSize < oneBlockCompressedSize;
		boolean fitsInOneCHK = bestCodec == null ? compressedDataSize < CHKBlock.DATA_LENGTH : compressedDataSize < CHKBlock.MAX_COMPRESSED_DATA_LENGTH;

		if((fitsInOneBlockAsIs || fitsInOneCHK) && origSize > Integer.MAX_VALUE)
			throw new InsertException(InsertException.INTERNAL_ERROR, "2GB+ should not encode to one block!", null);

		boolean noMetadata = ((block.clientMetadata == null) || block.clientMetadata.isTrivial()) && targetFilename == null;
		if(noMetadata && archiveType == null) {
			if(fitsInOneBlockAsIs) {
				if(persistent && (data instanceof NotPersistentBucket))
					data = fixNotPersistent(data, context);
				// Just insert it
				ClientPutState bi =
					createInserter(parent, data, codecNumber, ctx, cb, metadata, (int)origSize, -1, getCHKOnly, true, container, context, shouldFreeData, forSplitfile);
				if(logMINOR)
					Logger.minor(this, "Inserting without metadata: "+bi+" for "+this);
				cb.onTransition(this, bi, container);
				if(earlyEncode && bi instanceof SingleBlockInserter && isCHK)
					((SingleBlockInserter)bi).getBlock(container, context, true);
				bi.schedule(container, context);
				if(!isUSK)
					cb.onBlockSetFinished(this, container, context);
				started = true;
				if(persistent) {
					if(!parentWasActive)
						container.deactivate(parent, 1);
					block.nullData();
					block.removeFrom(container);
					block = null;
					removeFrom(container, context);
				}
				return;
			}
		}
		if (fitsInOneCHK) {
			// Insert single block, then insert pointer to it
			if(persistent && (data instanceof NotPersistentBucket)) {
				data = fixNotPersistent(data, context);
			}
			if(reportMetadataOnly) {
				if(persistent) container.activate(ctx, 1);
				SingleBlockInserter dataPutter = new SingleBlockInserter(parent, data, codecNumber, persistent ? FreenetURI.EMPTY_CHK_URI.clone() : FreenetURI.EMPTY_CHK_URI, ctx, cb, metadata, (int)origSize, -1, getCHKOnly, true, true, token, container, context, persistent, shouldFreeData, forSplitfile ? ctx.extraInsertsSplitfileHeaderBlock : ctx.extraInsertsSingleBlock);
				if(logMINOR)
					Logger.minor(this, "Inserting with metadata: "+dataPutter+" for "+this);
				Metadata meta = makeMetadata(archiveType, dataPutter.getURI(container, context));
				cb.onMetadata(meta, this, container, context);
				cb.onTransition(this, dataPutter, container);
				dataPutter.schedule(container, context);
				cb.onBlockSetFinished(this, container, context);
			} else {
				MultiPutCompletionCallback mcb = 
					new MultiPutCompletionCallback(cb, parent, token, persistent);
				if(persistent) container.activate(ctx, 1);
				SingleBlockInserter dataPutter = new SingleBlockInserter(parent, data, codecNumber, persistent ? FreenetURI.EMPTY_CHK_URI.clone() : FreenetURI.EMPTY_CHK_URI, ctx, mcb, metadata, (int)origSize, -1, getCHKOnly, true, false, token, container, context, persistent, shouldFreeData, forSplitfile ? ctx.extraInsertsSplitfileHeaderBlock : ctx.extraInsertsSingleBlock);
				if(logMINOR)
					Logger.minor(this, "Inserting data: "+dataPutter+" for "+this);
				Metadata meta = makeMetadata(archiveType, dataPutter.getURI(container, context));
				Bucket metadataBucket;
				try {
					metadataBucket = BucketTools.makeImmutableBucket(context.getBucketFactory(persistent), meta.writeToByteArray());
				} catch (IOException e) {
					Logger.error(this, "Caught "+e, e);
					throw new InsertException(InsertException.BUCKET_ERROR, e, null);
				} catch (MetadataUnresolvedException e) {
					// Impossible, we're not inserting a manifest.
					Logger.error(this, "Caught "+e, e);
					throw new InsertException(InsertException.INTERNAL_ERROR, "Got MetadataUnresolvedException in SingleFileInserter: "+e.toString(), null);
				}
				ClientPutState metaPutter = createInserter(parent, metadataBucket, (short) -1, ctx, mcb, true, (int)origSize, -1, getCHKOnly, true, container, context, true, false);
				if(logMINOR)
					Logger.minor(this, "Inserting metadata: "+metaPutter+" for "+this);
				mcb.addURIGenerator(metaPutter, container);
				mcb.add(dataPutter, container);
				cb.onTransition(this, mcb, container);
				Logger.minor(this, ""+mcb+" : data "+dataPutter+" meta "+metaPutter);
				mcb.arm(container, context);
				dataPutter.schedule(container, context);
				if(earlyEncode && metaPutter instanceof SingleBlockInserter)
					((SingleBlockInserter)metaPutter).getBlock(container, context, true);
				metaPutter.schedule(container, context);
				cb.onBlockSetFinished(this, container, context);
			}
			started = true;
			if(persistent) {
				if(!parentWasActive)
					container.deactivate(parent, 1);
				block.nullData();
				block.removeFrom(container);
				block = null;
				removeFrom(container, context);
			}
			return;
		}
		// Otherwise the file is too big to fit into one block
		// We therefore must make a splitfile
		// Job of SplitHandler: when the splitinserter has the metadata,
		// insert it. Then when the splitinserter has finished, and the
		// metadata insert has finished too, tell the master callback.
		if(reportMetadataOnly) {
			SplitFileInserter sfi = new SplitFileInserter(parent, cb, data, bestCodec, origSize, block.clientMetadata, ctx, getCHKOnly, metadata, token, archiveType, shouldFreeData, persistent, container, context);
			if(logMINOR)
				Logger.minor(this, "Inserting as splitfile: "+sfi+" for "+this);
			cb.onTransition(this, sfi, container);
			sfi.start(container, context);
			if(earlyEncode) sfi.forceEncode(container, context);
			if(persistent) {
				container.store(sfi);
				container.deactivate(sfi, 1);
			}
			block.nullData();
			block.nullMetadata();
			if(persistent) removeFrom(container, context);
		} else {
			SplitHandler sh = new SplitHandler();
			SplitFileInserter sfi = new SplitFileInserter(parent, sh, data, bestCodec, origSize, block.clientMetadata, ctx, getCHKOnly, metadata, token, archiveType, shouldFreeData, persistent, container, context);
			sh.sfi = sfi;
			if(logMINOR)
				Logger.minor(this, "Inserting as splitfile: "+sfi+" for "+sh+" for "+this);
			if(persistent)
				container.store(sh);
			cb.onTransition(this, sh, container);
			sfi.start(container, context);
			if(earlyEncode) sfi.forceEncode(container, context);
			if(persistent) {
				container.store(sfi);
				container.deactivate(sfi, 1);
			}
			started = true;
			if(persistent)
				container.store(this);
		}
		if(persistent) {
			if(!parentWasActive)
				container.deactivate(parent, 1);
		}
	}
	
	private Bucket fixNotPersistent(Bucket data, ClientContext context) throws InsertException {
		boolean skip = false;
		if(data instanceof SegmentedBucketChainBucket) {
			SegmentedBucketChainBucket seg = (SegmentedBucketChainBucket) data;
			Bucket[] buckets = seg.getBuckets();
			if(buckets.length == 1) {
				seg.clear();
				data = buckets[0];
				skip = true;
				if(logMINOR) Logger.minor(this, "Using bucket 0 of SegmentedBucketChainBucket");
			}
		}
		try {
			if(!skip) {
			if(logMINOR) Logger.minor(this, "Copying data from "+data+" length "+data.size());
			Bucket newData = context.persistentBucketFactory.makeBucket(data.size());
			BucketTools.copy(data, newData);
			data.free();
			data = newData;
			}
		} catch (IOException e) {
			Logger.error(this, "Caught "+e+" while copying non-persistent data", e);
			throw new InsertException(InsertException.BUCKET_ERROR, e, null);
		}
		// Note that SegmentedBCB *does* support splitting, so we don't need to do anything to the data
		// if it doesn't fit in a single block.
		return data;
	}

	private void tryCompress(ObjectContainer container, ClientContext context) throws InsertException {
		// First, determine how small it needs to be
		Bucket origData = block.getData();
		Bucket data = origData;
		int blockSize;
		int oneBlockCompressedSize;
		boolean dontCompress = ctx.dontCompress;
		
		if(persistent)
			container.activate(data, 1);
		long origSize = data.size();
		if(persistent)
			container.activate(block.desiredURI, 5);
		String type = block.desiredURI.getKeyType().toUpperCase();
		if(type.equals("SSK") || type.equals("KSK") || type.equals("USK")) {
			blockSize = SSKBlock.DATA_LENGTH;
			oneBlockCompressedSize = SSKBlock.MAX_COMPRESSED_DATA_LENGTH;
		} else if(type.equals("CHK")) {
			blockSize = CHKBlock.DATA_LENGTH;
			oneBlockCompressedSize = CHKBlock.MAX_COMPRESSED_DATA_LENGTH;
		} else {
			throw new InsertException(InsertException.INVALID_URI, "Unknown key type: "+type, null);
		}
		
		boolean tryCompress = (origSize > blockSize) && (!ctx.dontCompress) && (!dontCompress);
		if(tryCompress) {
			InsertCompressor.start(container, context, this, origData, oneBlockCompressedSize, context.getBucketFactory(persistent), persistent);
		} else {
			if(logMINOR) Logger.minor(this, "Not compressing "+origData+" size = "+origSize+" block size = "+blockSize);
			CompressionOutput output = new CompressionOutput(data, null);
			onCompressed(output, container, context);
		}
	}
	
	private Metadata makeMetadata(ARCHIVE_TYPE archiveType, FreenetURI uri) {
		Metadata meta = null;
		if(archiveType != null)
			meta = new Metadata(Metadata.ARCHIVE_MANIFEST, archiveType, null, uri, block.clientMetadata);
		else // redirect
			meta = new Metadata(Metadata.SIMPLE_REDIRECT, archiveType, null, uri, block.clientMetadata);
		if(targetFilename != null) {
			HashMap<String, Object> hm = new HashMap<String, Object>();
			hm.put(targetFilename, meta);
			meta = Metadata.mkRedirectionManifestWithMetadata(hm);
		}
		return meta;
	}

	/**
	 * Create an inserter, either for a USK or a single block.
	 * @param forSplitfile Whether this insert is above a splitfile. This
	 * affects whether we do multiple inserts of the same block. */
	private ClientPutState createInserter(BaseClientPutter parent, Bucket data, short compressionCodec, 
			InsertContext ctx, PutCompletionCallback cb, boolean isMetadata, int sourceLength, int token, boolean getCHKOnly, 
			boolean addToParent, ObjectContainer container, ClientContext context, boolean freeData, boolean forSplitfile) throws InsertException {
		
		FreenetURI uri = block.desiredURI;
		uri.checkInsertURI(); // will throw an exception if needed
		
		if(persistent) container.activate(ctx, 1);
		if(uri.getKeyType().equals("USK")) {
			try {
				return new USKInserter(parent, data, compressionCodec, uri, ctx, cb, isMetadata, sourceLength, token, 
					getCHKOnly, addToParent, this.token, container, context, freeData, persistent, forSplitfile ? ctx.extraInsertsSplitfileHeaderBlock : ctx.extraInsertsSingleBlock);
			} catch (MalformedURLException e) {
				throw new InsertException(InsertException.INVALID_URI, e, null);
			}
		} else {
			SingleBlockInserter sbi = 
				new SingleBlockInserter(parent, data, compressionCodec, uri, ctx, cb, isMetadata, sourceLength, token, 
						getCHKOnly, addToParent, false, this.token, container, context, persistent, freeData, forSplitfile ? ctx.extraInsertsSplitfileHeaderBlock : ctx.extraInsertsSingleBlock);
			// pass uri to SBI
			block.nullURI();
			if(persistent) container.store(block);
			return sbi;
		}
		
	}
	
	/**
	 * When we get the metadata, start inserting it to our target key.
	 * When we have inserted both the metadata and the splitfile,
	 * call the master callback.
	 * 
	 * This class has to be public so that db4o can access objectOnActivation
	 * through reflection.
	 */
	public class SplitHandler implements PutCompletionCallback, ClientPutState {

		ClientPutState sfi;
		ClientPutState metadataPutter;
		boolean finished;
		boolean splitInsertSuccess;
		boolean metaInsertSuccess;
		boolean splitInsertSetBlocks;
		boolean metaInsertSetBlocks;
		boolean metaInsertStarted;
		boolean metaFetchable;
		final boolean persistent;
		
		// A persistent hashCode is helpful in debugging, and also means we can put
		// these objects into sets etc when we need to.
		
		private final int hashCode;
		
		@Override
		public int hashCode() {
			return hashCode;
		}

		/**
		 * Create a SplitHandler from a stored progress SimpleFieldSet.
		 * @param forceMetadata If true, the insert is metadata, regardless of what the
		 * encompassing SplitFileInserter says (i.e. it's multi-level metadata).
		 * @throws ResumeException Thrown if the resume fails.
		 * @throws InsertException Thrown if some other error prevents the insert
		 * from starting.
		 */
		void start(SimpleFieldSet fs, boolean forceMetadata, ObjectContainer container, ClientContext context) throws ResumeException, InsertException {
			
			boolean parentWasActive = true;
			if(persistent) {
				parentWasActive = container.ext().isActive(parent);
				if(!parentWasActive)
					container.activate(parent, 1);
			}
			
			boolean meta = metadata || forceMetadata;
			
			// Don't include the booleans; wait for the callback.
			
			SimpleFieldSet sfiFS = fs.subset("SplitFileInserter");
			if(sfiFS == null)
				throw new ResumeException("No SplitFileInserter");
			ClientPutState newSFI, newMetaPutter = null;
			newSFI = new SplitFileInserter(parent, this, forceMetadata ? null : block.clientMetadata, ctx, getCHKOnly, meta, token, archiveType, sfiFS, container, context);
			if(logMINOR) Logger.minor(this, "Starting "+newSFI+" for "+this);
			fs.removeSubset("SplitFileInserter");
			SimpleFieldSet metaFS = fs.subset("MetadataPutter");
			if(metaFS != null) {
				try {
					String type = metaFS.get("Type");
					if(type.equals("SplitFileInserter")) {
						// FIXME insertAsArchiveManifest ?!?!?!
						newMetaPutter = 
							new SplitFileInserter(parent, this, null, ctx, getCHKOnly, true, token, archiveType, metaFS, container, context);
					} else if(type.equals("SplitHandler")) {
						newMetaPutter = new SplitHandler();
						((SplitHandler)newMetaPutter).start(metaFS, true, container, context);
					}
				} catch (ResumeException e) {
					newMetaPutter = null;
					Logger.error(this, "Caught "+e, e);
					// Will be reconstructed later
				}
			}
			if(logMINOR) Logger.minor(this, "Metadata putter "+metadataPutter+" for "+this);
			fs.removeSubset("MetadataPutter");
			synchronized(this) {
				sfi = newSFI;
				metadataPutter = newMetaPutter;
			}
			if(persistent) {
				container.store(this);
				if(!parentWasActive)
					container.deactivate(parent, 1);
			}
		}

		public SplitHandler() {
			// Default constructor
			this.persistent = SingleFileInserter.this.persistent;
			this.hashCode = super.hashCode();
		}

		public synchronized void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
			if(persistent) { // FIXME debug-point
				if(logMINOR) Logger.minor(this, "Transition: "+oldState+" -> "+newState);
			}
			if(oldState == sfi)
				sfi = newState;
			if(oldState == metadataPutter)
				metadataPutter = newState;
			if(persistent)
				container.store(this);
		}
		
		public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) {
				container.activate(block, 2);
			}
			if(logMINOR) Logger.minor(this, "onSuccess("+state+") for "+this);
			boolean lateStart = false;
			ClientPutState toRemove = null;
			synchronized(this) {
				if(finished){
					return;
				}
				if(state == sfi) {
					if(logMINOR) Logger.minor(this, "Splitfile insert succeeded for "+this+" : "+state);
					splitInsertSuccess = true;
					if(!metaInsertSuccess && !metaInsertStarted) {
						lateStart = true;
						// Cannot remove yet because not created metadata inserter yet.
					} else {
						sfi = null;
						if(logMINOR) Logger.minor(this, "Metadata already started for "+this+" : success="+metaInsertSuccess+" started="+metaInsertStarted);
					}
					toRemove = state;
				} else if(state == metadataPutter) {
					if(logMINOR) Logger.minor(this, "Metadata insert succeeded for "+this+" : "+state);
					metaInsertSuccess = true;
					metadataPutter = null;
					toRemove = state;
				} else {
					Logger.error(this, "Unknown: "+state+" for "+this, new Exception("debug"));
				}
				if(splitInsertSuccess && metaInsertSuccess) {
					if(logMINOR) Logger.minor(this, "Both succeeded for "+this);
					finished = true;
					if(freeData)
						block.free(container);
					else {
						block.nullData();
						if(persistent)
							container.store(block);
					}
				}
			}
			if(lateStart) {
				if(!startMetadata(container, context))
					toRemove = null;
				else {
					synchronized(this) {
						sfi = null;
					}
				}
			}
			if(toRemove != null && persistent)
				toRemove.removeFrom(container, context);
			if(persistent)
				container.store(this);
			if(finished) {
				if(persistent)
					container.activate(cb, 1);
				cb.onSuccess(this, container, context);
				if(persistent)
					container.deactivate(cb, 1);
			}
		}

		public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) {
				container.activate(block, 1);
			}
			boolean toFail = true;
			boolean toRemove = false;
			synchronized(this) {
				if(logMINOR)
					Logger.minor(this, "onFailure(): "+e+" on "+state+" on "+this+" sfi = "+sfi+" metadataPutter = "+metadataPutter);
				if(state == sfi) {
					toRemove = true;
					sfi = null;
					if(metadataPutter != null) {
						if(persistent) container.store(this);
					}
				} else if(state == metadataPutter) {
					toRemove = true;
					metadataPutter = null;
					if(sfi != null) {
						if(persistent) container.store(this);
					}
				} else {
					Logger.error(this, "onFailure() on unknown state "+state+" on "+this, new Exception("debug"));
				}
				if(finished){
					toFail = false; // Already failed
				}
			}
			if(toRemove && persistent)
				state.removeFrom(container, context);
			// fail() will cancel the other one, so we don't need to.
			// When it does, it will come back here, and we won't call fail(), because fail() has already set finished = true.
			if(toFail)
			fail(e, container, context);
		}

		public void onMetadata(Metadata meta, ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) {
				container.activate(cb, 1);
				container.activate(block, 2);
			}
			InsertException e = null;
			if(logMINOR) Logger.minor(this, "Got metadata for "+this+" from "+state);
			synchronized(this) {
				if(finished) return;
				if(reportMetadataOnly) {
					if(state != sfi) {
						Logger.error(this, "Got metadata from unknown object "+state+" when expecting to report metadata");
						return;
					}
					metaInsertSuccess = true;
				} else if(state == metadataPutter) {
					Logger.error(this, "Got metadata for metadata");
					e = new InsertException(InsertException.INTERNAL_ERROR, "Did not expect to get metadata for metadata inserter", null);
				} else if(state != sfi) {
					Logger.error(this, "Got metadata from unknown state "+state+" sfi="+sfi+" metadataPutter="+metadataPutter+" on "+this+" persistent="+persistent, new Exception("debug"));
					e = new InsertException(InsertException.INTERNAL_ERROR, "Got metadata from unknown state", null);
				} else {
					// Already started metadata putter ? (in which case we've got the metadata twice)
					if(metadataPutter != null) return;
				}
			}
			if(reportMetadataOnly) {
				if(persistent)
					container.store(this);
				cb.onMetadata(meta, this, container, context);
				return;
			}
			if(e != null) {
				onFailure(e, state, container, context);
				return;
			}
			
			byte[] metaBytes;
			if(persistent)
				// Load keys
				container.activate(meta, 100);
			try {
				metaBytes = meta.writeToByteArray();
			} catch (MetadataUnresolvedException e1) {
				Logger.error(this, "Impossible: "+e1, e1);
				fail((InsertException)new InsertException(InsertException.INTERNAL_ERROR, "MetadataUnresolvedException in SingleFileInserter.SplitHandler: "+e1, null).initCause(e1), container, context);
				return;
			}
			
			String metaPutterTargetFilename = targetFilename;
			
			if(targetFilename != null) {
				
				if(metaBytes.length <= Short.MAX_VALUE) {
					HashMap<String, Object> hm = new HashMap<String, Object>();
					hm.put(targetFilename, meta);
					meta = Metadata.mkRedirectionManifestWithMetadata(hm);
					metaPutterTargetFilename = null;
					try {
						metaBytes = meta.writeToByteArray();
					} catch (MetadataUnresolvedException e1) {
						Logger.error(this, "Impossible (2): "+e1, e1);
						fail((InsertException)new InsertException(InsertException.INTERNAL_ERROR, "MetadataUnresolvedException in SingleFileInserter.SplitHandler(2): "+e1, null).initCause(e1), container, context);
						return;
					}
				}
			}
			
			Bucket metadataBucket;
			try {
				metadataBucket = BucketTools.makeImmutableBucket(context.getBucketFactory(persistent), metaBytes);
			} catch (IOException e1) {
				InsertException ex = new InsertException(InsertException.BUCKET_ERROR, e1, null);
				fail(ex, container, context);
				return;
			}
			InsertBlock newBlock = new InsertBlock(metadataBucket, null, block.desiredURI);
				synchronized(this) {
					metadataPutter = new SingleFileInserter(parent, this, newBlock, true, ctx, false, getCHKOnly, false, token, archiveType, true, metaPutterTargetFilename, earlyEncode, true, persistent);
					// If EarlyEncode, then start the metadata insert ASAP, to get the key.
					// Otherwise, wait until the data is fetchable (to improve persistence).
					if(logMINOR)
						Logger.minor(this, "Created metadata putter for "+this+" : "+metadataPutter+" bucket "+metadataBucket+" size "+metadataBucket.size());
					if(persistent)
						container.store(this);
					if(!(earlyEncode || splitInsertSuccess)) return;
				}
				if(logMINOR) Logger.minor(this, "Putting metadata on "+metadataPutter+" from "+sfi+" ("+((SplitFileInserter)sfi).getLength()+ ')');
			if(!startMetadata(container, context)) {
				Logger.error(this, "onMetadata() yet unable to start metadata due to not having all URIs?!?!");
				fail(new InsertException(InsertException.INTERNAL_ERROR, "onMetadata() yet unable to start metadata due to not having all URIs", null), container, context);
				return;
			}
			ClientPutState toRemove = null;
			synchronized(this) {
				if(splitInsertSuccess && sfi != null) {
					toRemove = sfi;
					sfi = null;
				}
			}
			if(toRemove != null && persistent)
				toRemove.removeFrom(container, context);
				
		}

		private void fail(InsertException e, ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "Failing: "+e, e);
			ClientPutState oldSFI = null;
			ClientPutState oldMetadataPutter = null;
			synchronized(this) {
				if(finished){
					return;
				}
				finished = true;
				oldSFI = sfi;
				oldMetadataPutter = metadataPutter;
			}
			if(persistent) {
				container.store(this);
				if(oldSFI != null)
					container.activate(oldSFI, 1);
				if(oldMetadataPutter != null)
					container.activate(oldMetadataPutter, 1);
			}
			if(oldSFI != null)
				oldSFI.cancel(container, context);
			if(oldMetadataPutter != null)
				oldMetadataPutter.cancel(container, context);
			if(persistent) {
				container.activate(block, 2);
				container.activate(cb, 1);
			}
			synchronized(this) {
				if(freeData)
					block.free(container);
				else {
					block.nullData();
					if(persistent)
						container.store(block);
				}
			}
			cb.onFailure(e, this, container, context);
		}

		public BaseClientPutter getParent() {
			return parent;
		}

		public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) // FIXME debug-point
				if(logMINOR) Logger.minor(this, "onEncode() for "+this+" : "+state+" : "+key);
			synchronized(this) {
				if(state != metadataPutter) {
					if(logMINOR) Logger.minor(this, "ignored onEncode() for "+this+" : "+state);
					return;
				}
			}
			if(persistent) container.activate(cb, 1);
			cb.onEncode(key, this, container, context);
		}

		public void cancel(ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "Cancelling "+this);
			ClientPutState oldSFI = null;
			ClientPutState oldMetadataPutter = null;
			synchronized(this) {
				oldSFI = sfi;
				oldMetadataPutter = metadataPutter;
			}
			if(persistent) {
				container.store(this);
				if(oldSFI != null)
					container.activate(oldSFI, 1);
				if(oldMetadataPutter != null)
					container.activate(oldMetadataPutter, 1);
			}
			if(oldSFI != null)
				oldSFI.cancel(container, context);
			if(oldMetadataPutter != null)
				oldMetadataPutter.cancel(container, context);
			
			// FIXME in the other cases, fail() and onSuccess(), we only free when
			// we set finished. But we haven't set finished here. Can we rely on 
			// the callback and not do anything here? Note that it is in fact safe
			// to double-free, it's not safe to not free.
			if(freeData) {
				if(persistent)
					container.activate(block, 2);
				block.free(container);
			} else {
				block.nullData();
				if(persistent)
					container.store(block);
			}
		}

		public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
			synchronized(this) {
				if(state == sfi)
					splitInsertSetBlocks = true;
				else if (state == metadataPutter)
					metaInsertSetBlocks = true;
				if(persistent)
					container.store(this);
				if(!(splitInsertSetBlocks && metaInsertSetBlocks)) 
					return;
			}
			if(persistent)
				container.activate(cb, 1);
			cb.onBlockSetFinished(this, container, context);
		}

		public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
			if(persistent)
				container.activate(sfi, 1);
			sfi.schedule(container, context);
		}

		public Object getToken() {
			return token;
		}

		public void onFetchable(ClientPutState state, ObjectContainer container) {

			if(persistent) // FIXME debug-point
				if(logMINOR) Logger.minor(this, "onFetchable on "+this);
			
			if(logMINOR) Logger.minor(this, "onFetchable("+state+ ')');
			
			boolean meta;
			
			synchronized(this) {
				meta = (state == metadataPutter);
				if(meta) {
					if(!metaInsertStarted) {
						Logger.error(this, "Metadata insert not started yet got onFetchable for it: "+state+" on "+this);
					}
					if(logMINOR) Logger.minor(this, "Metadata fetchable"+(metaFetchable?"":" already"));
					if(metaFetchable) return;
					metaFetchable = true;
					if(persistent)
						container.store(this);
				} else {
					if(state != sfi) {
						Logger.error(this, "onFetchable for unknown state "+state);
						return;
					}
					if(persistent)
						container.store(this);
					if(logMINOR) Logger.minor(this, "Data fetchable");
					if(metaInsertStarted) return;
				}
			}
			
			if(meta) {
				if(persistent)
					container.activate(cb, 1);
				cb.onFetchable(this, container);
			}
		}
		
		/**
		 * Start fetching metadata. There is an exceptional case where we don't have all the URIs yet; if so,
		 * we force encode, and don't start fetching.
		 * @param container
		 * @param context
		 * @return True unless we don't have all URI's and so can't remove sfi.
		 */
		private boolean startMetadata(ObjectContainer container, ClientContext context) {
			if(persistent) // FIXME debug-point
				if(logMINOR) Logger.minor(this, "startMetadata() on "+this);
			try {
				ClientPutState putter;
				ClientPutState splitInserter;
				synchronized(this) {
					if(metaInsertStarted) return true;
					if(persistent && metadataPutter != null)
						container.activate(metadataPutter, 1);
					putter = metadataPutter;
					if(putter == null) {
						if(logMINOR) Logger.minor(this, "Cannot start metadata yet: no metadataPutter");
					} else
						metaInsertStarted = true;
					splitInserter = sfi;
				}
				if(persistent)
					container.store(this);
				if(putter != null) {
					if(logMINOR) Logger.minor(this, "Starting metadata inserter: "+putter+" for "+this);
					putter.schedule(container, context);
					if(logMINOR) Logger.minor(this, "Started metadata inserter: "+putter+" for "+this);
					return true;
				} else {
					// Get all the URIs ASAP so we can start to insert the metadata.
					// Unless earlyEncode is enabled, this is an error or at least a rare case, indicating e.g. we've lost a URI.
					Logger.error(this, "startMetadata() calling forceEncode() on "+splitInserter+" for "+this, new Exception("error"));
					if(persistent)
						container.activate(splitInserter, 1);
					((SplitFileInserter)splitInserter).forceEncode(container, context);
					return false;
				}
			} catch (InsertException e1) {
				Logger.error(this, "Failing "+this+" : "+e1, e1);
				fail(e1, container, context);
				return true;
			}
		}
		
		public void objectOnActivate(ObjectContainer container) {
			// Chain to containing class, since we use its members extensively.
			container.activate(SingleFileInserter.this, 1);
		}

		public void removeFrom(ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "removeFrom() on "+this);
			container.delete(this);
			// Remove parent as well, since we always transition from parent to SH i.e. it will not get a removeFrom().
			SingleFileInserter.this.removeFrom(container, context);
		}
		
		public boolean objectCanUpdate(ObjectContainer container) {
			if(logDEBUG)
				Logger.debug(this, "objectCanUpdate() on "+this, new Exception("debug"));
			return true;
		}
		
		public boolean objectCanNew(ObjectContainer container) {
			if(finished)
				Logger.error(this, "objectCanNew but finished on "+this, new Exception("error"));
			else if(logDEBUG)
				Logger.debug(this, "objectCanNew() on "+this, new Exception("debug"));
			return true;
		}
		
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Cancel "+this);
		synchronized(this) {
			if(cancelled) return;
			cancelled = true;
		}
		if(freeData) {
			if(persistent)
				container.activate(block, 1);
			block.free(container);
		}
		if(persistent)
			container.store(this);
		if(persistent)
			container.activate(cb, 1);
		// Must call onFailure so get removeFrom()'ed
		cb.onFailure(new InsertException(InsertException.CANCELLED), this, container, context);
	}

	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		start(null, container, context);
	}

	public Object getToken() {
		return token;
	}

	public void onStartCompression(COMPRESSOR_TYPE ctype, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(ctx, 2);
		}
		if(parent == cb) {
			if(ctx == null) throw new NullPointerException();
			if(ctx.eventProducer == null) throw new NullPointerException();
			ctx.eventProducer.produceEvent(new StartedCompressionEvent(ctype), container, context);
		}
	}
	
	boolean cancelled() {
		return cancelled;
	}
	
	boolean started() {
		return started;
	}

	public void removeFrom(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "removeFrom() on "+this, new Exception("debug"));
		// parent removes self
		// token is passed in, creator of token is responsible for removing it
		if(block != null) {
			container.activate(block, 1);
			block.removeFrom(container);
		}
		// ctx is passed in, creator is responsible for removing it
		// cb removes itself
		container.delete(this);
	}
	
	public boolean objectCanUpdate(ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "objectCanUpdate() on "+this, new Exception("debug"));
		return true;
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "objectCanNew() on "+this, new Exception("debug"));
		return true;
	}
	
}
