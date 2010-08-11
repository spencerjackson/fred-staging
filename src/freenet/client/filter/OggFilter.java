/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.HashMap;
import java.util.LinkedList;

/** Filters Ogg container files. These containers contain one or more
 * logical bitstreams of data encapsulated into a physical bitstream.
 * The data is broken into variable length pages, consisting of a header
 * and 0-255 segments of 0-255 bytes. For more details refer to
 * <a href="http://www.xiph.org/ogg/doc/rfc3533.txt">http://www.xiph.org/ogg/doc/rfc3533.txt</a>
 * @author sajack
 */
public class OggFilter implements ContentDataFilter{

	public void readFilter(InputStream input, OutputStream output,
			String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
		HashMap<Integer, OggBitstreamFilter> streamFilters = new HashMap<Integer, OggBitstreamFilter>();
		LinkedList<OggPage> splitPages = new LinkedList<OggPage>();
		DataInputStream in = new DataInputStream(new BufferedInputStream(input, 255));
		OggPage page = null;
		OggPage nextPage = OggPage.readPage(in);
		boolean running = true;
		while(running) {
			page = nextPage;
			try {
				nextPage = OggPage.readPage(in);
			} catch (EOFException e) {
				running = false;
			}
			OggBitstreamFilter filter = null;
			if(streamFilters.containsKey(page.getSerial())) {
				filter = streamFilters.get(page.getSerial());
			} else {
				filter = OggBitstreamFilter.getBitstreamFilter(page);
				streamFilters.put(page.getSerial(), filter);
			}
			if(filter == null) continue;
			//Don't write a continuous pages unless they are all valid
			if(page.headerValid() && filter.parse(page) && !hasValidSubpage(page, nextPage)) {
				splitPages.add(page);
				if(page.isFinalPacket()) {
					while(!splitPages.isEmpty()) {
						OggPage part = splitPages.remove();
						output.write(part.toArray());
					}
				}
			} else if(!splitPages.isEmpty()) splitPages.clear();
		}
		output.flush();
	}

	/**Searches for valid pages hidden inside this page
	 * @return whether or not a hidden page exists
	 * @throws IOException
	 */
	private boolean hasValidSubpage(OggPage page, OggPage nextPage) throws IOException {
		OggPage subpage = null;
		int pageCount = 0;
		try{
			//Populate a byte array with all the data in which a subpage might hide
			ByteArrayOutputStream data = new ByteArrayOutputStream();
			data.write(page.toArray());
			if(nextPage != null) data.write(nextPage.toArray());
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(data.toByteArray()));
			while(true) {
				OggPage.seekToPage(in);
				in.mark(65307);
				subpage = new OggPage(in);
				if(subpage.headerValid()) {
					pageCount++;
				}
				in.reset();
				in.skip(1); //Break the lock on the current page
			}
		} catch(EOFException e) {
			//We've ran out of data to read. Break.
		}
		return (pageCount > 2 || hasValidSubpage(page));
	}

	private boolean hasValidSubpage(OggPage page) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(page.toArray()));
		in.skip(1); //Break alignment with the first page
		try {
			while(true) {
				OggPage subpage = OggPage.readPage(in);
				if(subpage.headerValid()) return true;
			}
		} catch(EOFException e) {
			//We've ran out of data to read. Break.
		}
		return false;
	}

	public void writeFilter(InputStream input, OutputStream output,
			String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
		// TODO Auto-generated method stub
		
	}
}