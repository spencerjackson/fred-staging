package freenet.clients.http.updateableelements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Map.Entry;

import freenet.client.FetchException;
import freenet.clients.http.FProxyFetchInProgress;
import freenet.clients.http.FProxyFetchResult;
import freenet.clients.http.FProxyFetchTracker;
import freenet.clients.http.FProxyFetchWaiter;
import freenet.clients.http.FProxyToadlet;
import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.filter.HTMLFilter.ParsedTag;
import freenet.keys.FreenetURI;
import freenet.support.Base64;
import freenet.support.HTMLNode;
import freenet.support.Logger;

/** A pushed image, the progress is shown with the ImageCreatorToadlet */
public class ImageElement extends BaseUpdateableElement {

	private static volatile boolean	logMINOR;

	static {
		Logger.registerClass(ImageElement.class);
	}

	/** The tracker that the Fetcher can be acquired */
	public FProxyFetchTracker		tracker;
	/** The URI of the download this progress bar shows */
	public FreenetURI				key;
	/** The maxSize */
	public long						maxSize;
	/** The FetchListener that gets notified when the download progresses */
	private NotifierFetchListener	fetchListener;

	private ParsedTag				originalImg;

	private int						randomNumber	= new Random().nextInt();

	private boolean					wasError		= false;

	public static ImageElement createImageElement(FProxyFetchTracker tracker,FreenetURI key,long maxSize,ToadletContext ctx){
		return createImageElement(tracker,key,maxSize,ctx,-1,-1, null);
	}
	
	public static ImageElement createImageElement(FProxyFetchTracker tracker,FreenetURI key,long maxSize,ToadletContext ctx,int width,int height, String name){
		Map<String,String> attributes=new HashMap<String, String>();
		attributes.put("src", key.toString());
		if(width!=-1){
			attributes.put("width", String.valueOf(width));
		}
		if(height!=-1){
			attributes.put("height", String.valueOf(height));
		}
		if(name != null) {
			attributes.put("alt", name);
			attributes.put("title", name);
		}
		return new ImageElement(tracker,key,maxSize,ctx,new ParsedTag("img", attributes));
	}
	
	public ImageElement(FProxyFetchTracker tracker, FreenetURI key, long maxSize, ToadletContext ctx, ParsedTag originalImg) {
		super("span", ctx);
		long now = System.currentTimeMillis();
		if (logMINOR) {
			Logger.minor(this, "ImageElement creating for uri:" + key);
		}
		this.originalImg = originalImg;
		this.tracker = tracker;
		this.key = key;
		this.maxSize = maxSize;
		init();
		// Creates and registers the FetchListener
		fetchListener = new NotifierFetchListener(((SimpleToadletServer) ctx.getContainer()).pushDataManager, this);
		((SimpleToadletServer) ctx.getContainer()).getTicker().queueTimedJob(new Runnable() {

			public void run() {
				try {
					FProxyFetchWaiter waiter = ImageElement.this.tracker.makeFetcher(ImageElement.this.key, ImageElement.this.maxSize);
					ImageElement.this.tracker.getFetchInProgress(ImageElement.this.key, ImageElement.this.maxSize).addListener(fetchListener);
					ImageElement.this.tracker.getFetchInProgress(ImageElement.this.key, ImageElement.this.maxSize).close(waiter);
				} catch (FetchException fe) {
					if (fe.newURI != null) {
						try {
							ImageElement.this.key = fe.newURI;
							FProxyFetchWaiter waiter = ImageElement.this.tracker.makeFetcher(ImageElement.this.key, ImageElement.this.maxSize);
							ImageElement.this.tracker.getFetchInProgress(ImageElement.this.key, ImageElement.this.maxSize).addListener(fetchListener);
							ImageElement.this.tracker.getFetchInProgress(ImageElement.this.key, ImageElement.this.maxSize).close(waiter);
						} catch (FetchException fe2) {
							wasError = true;
						}
					}
				}
				fetchListener.onEvent();
			}
		}, 0);

		if (logMINOR) {
			Logger.minor(this, "ImageElement creating finished in:" + (System.currentTimeMillis() - now) + " ms");
		}
	}

	@Override
	public void dispose() {
		if (logMINOR) {
			Logger.minor(this, "Disposing ImageElement");
		}
		FProxyFetchInProgress progress = tracker.getFetchInProgress(key, maxSize);
		if (progress != null) {
			progress.removeListener(fetchListener);
			if (logMINOR) {
				Logger.minor(this, "canCancel():" + progress.canCancel());
			}
			progress.requestImmediateCancel();
			if (progress.canCancel()) {
				tracker.run();
			}
		}
	}

	@Override
	public String getUpdaterId(String requestId) {
		return getId(key, randomNumber);
	}

	public static String getId(FreenetURI uri, int randomNumber) {
		return Base64.encodeStandard(("image[URI:" + uri.toString() + ",random:" + randomNumber + "]").getBytes());
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.IMAGE_ELEMENT_UPDATER;
	}

	@Override
	public void updateState(boolean initial) {
		if (logMINOR) {
			Logger.minor(this, "Updating ImageElement for url:" + key);
		}
		children.clear();
		HTMLNode whenJsEnabled = new HTMLNode("span", "class", "jsonly ImageElement");
		addChild(whenJsEnabled);
		// When js disabled
		addChild("noscript").addChild(makeHtmlNodeForParsedTag(originalImg));
		if (initial) {
			if (wasError) {
				whenJsEnabled.addChild(makeHtmlNodeForParsedTag(originalImg));
			} else {
				Map<String, String> attr = originalImg.getAttributesAsMap();
				String sizePart = new String();
				if (attr.containsKey("width") && attr.containsKey("height")) {
					sizePart = "&width=" + attr.get("width") + "&height=" + attr.get("height");
				}
				attr.put("src", "/imagecreator/?text=+"+FProxyToadlet.l10n("imageinitializing")+"+" + sizePart);
				whenJsEnabled.addChild(makeHtmlNodeForParsedTag(new ParsedTag(originalImg, attr)));
				whenJsEnabled.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "fetchedBlocks", String.valueOf(0) });
				whenJsEnabled.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "requiredBlocks", String.valueOf(1) });

			}
		} else {
			FProxyFetchResult fr = null;
			FProxyFetchWaiter waiter = null;
			try {
				try {
					waiter = tracker.makeFetcher(key, maxSize);
					fr = waiter.getResultFast();
				} catch (FetchException fe) {
					whenJsEnabled.addChild("div", "error");
				}
				if (fr == null) {
					whenJsEnabled.addChild("div", "No fetcher found");
				} else {

					if (fr.isFinished() && fr.hasData()) {
						if (logMINOR) {
							Logger.minor(this, "ImageElement is completed");
						}
						whenJsEnabled.addChild(makeHtmlNodeForParsedTag(originalImg));
					} else if (fr.failed != null) {
						if (logMINOR) {
							Logger.minor(this, "ImageElement is errorous");
						}
						whenJsEnabled.addChild(makeHtmlNodeForParsedTag(originalImg));
					} else {
						if (logMINOR) {
							Logger.minor(this, "ImageElement is still in progress");
						}
						int total = fr.requiredBlocks;
						int fetchedPercent = (int) (fr.fetchedBlocks / (double) total * 100);
						Map<String, String> attr = originalImg.getAttributesAsMap();
						String sizePart = new String();
						if (attr.containsKey("width") && attr.containsKey("height")) {
							sizePart = "&width=" + attr.get("width") + "&height=" + attr.get("height");
						}
						attr.put("src", "/imagecreator/?text=" + fetchedPercent + "%25" + sizePart);
						whenJsEnabled.addChild(makeHtmlNodeForParsedTag(new ParsedTag(originalImg, attr)));
						whenJsEnabled.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "fetchedBlocks", String.valueOf(fr.fetchedBlocks) });
						whenJsEnabled.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "requiredBlocks", String.valueOf(fr.requiredBlocks) });
					}
				}
			} finally {
				if (waiter != null) {
					tracker.getFetchInProgress(key, maxSize).close(waiter);
				}
				if (fr != null) {
					tracker.getFetchInProgress(key, maxSize).close(fr);
				}
			}
		}
	}

	private HTMLNode makeHtmlNodeForParsedTag(ParsedTag pt) {
		List<String> attributeNames = new ArrayList<String>();
		List<String> attributeValues = new ArrayList<String>();
		for (Entry<String, String> att : pt.getAttributesAsMap().entrySet()) {
			attributeNames.add(att.getKey());
			attributeValues.add(att.getValue());
		}
		return new HTMLNode(pt.element, attributeNames.toArray(new String[] {}), attributeValues.toArray(new String[] {}));
	}

	@Override
	public String toString() {
		return "ImageElement[key:" + key + ",maxSize:" + maxSize + ",originalImg:" + originalImg + ",updaterId:" + getUpdaterId(null) + "]";
	}

}