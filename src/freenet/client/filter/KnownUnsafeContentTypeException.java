/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import java.util.LinkedList;
import java.util.List;

import freenet.l10n.NodeL10n;
import freenet.support.HTMLEncoder;

public class KnownUnsafeContentTypeException extends UnsafeContentTypeException {
	private static final long serialVersionUID = -1;
	MIMEType type;
	
	public KnownUnsafeContentTypeException(MIMEType type) {
		this.type = type;
	}
	
	
	@Override
	public String getMessage() {
		StringBuilder sb = new StringBuilder();
		sb.append(l10n("knownUnsafe"));
		sb.append(l10n("noFilter"));
		
		return sb.toString();
	}

	@Override
	public String[] details() {
		List<String> details = new LinkedList<String>();
		if(type.dangerousInlines) details.add(l10n("dangerousInlineLabel")+l10n("dangerousInline"));
		if(type.dangerousLinks) details.add(l10n("dangerousLinksLabel")+l10n("dangerousLinks"));
		if(type.dangerousScripting) details.add(l10n("dangerousScriptsLabel")+l10n("dangerousScripts"));
		if(type.dangerousScripting) details.add(l10n("dangerousMetadataLabel")+l10n("dangerousMetadata"));
		return (String[]) details.toArray();
	}

	@Override
	public String getHTMLEncodedTitle() {
		return l10n("title", "type", HTMLEncoder.encode(type.primaryMimeType));
	}

	@Override
	public String getRawTitle() {
		return l10n("title", "type", type.primaryMimeType);
	}
	
	private static String l10n(String key) {
		return NodeL10n.getBase().getString("KnownUnsafeContentTypeException."+key);
	}

	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("KnownUnsafeContentTypeException."+key, pattern, value);
	}

}
