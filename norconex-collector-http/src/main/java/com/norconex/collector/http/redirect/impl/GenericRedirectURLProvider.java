/* Copyright 2015 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.collector.http.redirect.impl;

import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;

import javax.xml.stream.XMLStreamException;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang3.CharEncoding;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.RequestLine;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.tika.utils.CharsetUtils;

import com.norconex.collector.http.redirect.IRedirectURLProvider;
import com.norconex.commons.lang.config.ConfigurationUtil;
import com.norconex.commons.lang.config.IXMLConfigurable;
import com.norconex.commons.lang.url.HttpURL;
import com.norconex.commons.lang.xml.EnhancedXMLStreamWriter;

/**
 * <p>Provide redirect URLs by grabbing them from the HTTP Response 
 * <code>Location</code> header value. The URL is made absolute and
 * an attempt is made to fix possible character encoding issues.</p>
 * 
 * <p>
 * The <a href="http://www.ietf.org/rfc/rfc2616.txt">RFC 2616</a> 
 * specification mentions that the <code>Location</code> header 
 * should contain a URI as defined by the 
 * <a href="http://www.ietf.org/rfc/rfc1630.txt">RFC 1630</a> specification.
 * The later requires that a URI be 7-bit ASCII with any special characters
 * URL encoded.  
 * Some redirect URLs do not conform to that so we apply the following logic
 * in an attempt to fix them:
 * </p>
 * <ul>
 *   <li>
 *     Does the URL contains only ASCII characters (code points &lt;= 128)?
 *     <ul>
 *       <li>
 *         Yes: No attempt to fix it is made.
 *       </li>
 *       <li>
 *         No: Does the HTTP response specify a character encoding in the 
 *         <code>Content-Type</code> header?
 *         <ul>
 *           <li>
 *             Yes: Treat URL as being encoded in that character encoding.
 *           </li>
 *           <li>
 *             No: Is the fallback character encoding set?
 *             <ul>
 *               <li>
 *                 Yes: Treat URL as being encoded with the fallback character
 *                 encoding.
 *               </li>
 *               <li>
 *                 No: Treat URL as being encoded with UTF-8.
 *               </li>
 *             </ul>
 *           </li>
 *         </ul>
 *       </li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <h3>XML configuration usage:</h3>
 * <pre>
 *  &lt;redirectURLProvider 
 *      class="com.norconex.collector.http.redirect.impl.GenericRedirectURLProvider"
 *      fallbackCharset="(character encoding)" /&gt;
 * </pre> 
 * @author Pascal Essiembre
 * @since 2.4.0
 */
public class GenericRedirectURLProvider 
        implements IRedirectURLProvider, IXMLConfigurable {

    private static final Logger LOG = 
            LogManager.getLogger(GenericRedirectURLProvider.class);
    
    public static final String DEFAULT_FALLBACK_CHARSET = CharEncoding.UTF_8;

    private static final int ASCII_MAX_CODEPOINT = 128;
    
    private String fallbackCharset = DEFAULT_FALLBACK_CHARSET; 

    public String getFallbackCharset() {
        return fallbackCharset;
    }
    public void setFallbackCharset(String fallbackCharset) {
        this.fallbackCharset = fallbackCharset;
    }

    @Override
    public String provideRedirectURL(HttpRequest request,
            HttpResponse response, HttpContext context) {
        HttpRequest currentReq = (HttpRequest) context.getAttribute( 
                HttpCoreContext.HTTP_REQUEST);
        HttpHost currentHost = (HttpHost)  context.getAttribute( 
                HttpCoreContext.HTTP_TARGET_HOST);
        
        String originalURL = toAbsoluteURI(currentHost, currentReq);
        
        //--- Location ---
        Header hl = response.getLastHeader(HttpHeaders.LOCATION);
        if (hl == null) {
            //TODO should throw exception instead?
            LOG.error("Redirect detected to a null Location for: " 
                    + toAbsoluteURI(currentHost, currentReq));
            return null;
        }
        String redirectLocation = hl.getValue();
        
        //--- Charset ---
        String charset = null;
        Header hc = response.getLastHeader("Content-Type");
        if (hc != null) {
            String contentType = hc.getValue();
            if (contentType.contains(";")) {
                charset = StringUtils.substringAfterLast(
                        contentType, "charset=");
            }
        }
        if (StringUtils.isBlank(charset)) {
            charset = fallbackCharset;
        }
        
        //--- Build/fix redirect URL ---
        String targetURL = HttpURL.toAbsolute(originalURL, redirectLocation);
        targetURL = resolveRedirectURL(targetURL, charset);
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("URL redirect: " + originalURL + " -> " + targetURL);
        }
        return targetURL;
    }
    
    private String toAbsoluteURI(HttpHost host, HttpRequest req) {
        HttpRequest originalReq = req;
        
        // Check if we can get full URL from a nested request, to keep
        // the #fragment, if present.
        if (req instanceof HttpRequestWrapper) {
            originalReq = ((HttpRequestWrapper) req).getOriginal();
        }
        if (originalReq instanceof HttpRequestBase) {
            return ((HttpRequestBase) originalReq).getURI().toString();
        }

        // Else, built it
        if (originalReq instanceof HttpUriRequest) {
            HttpUriRequest httpReq = (HttpUriRequest) originalReq;
            if (httpReq.getURI().isAbsolute()) {
                return httpReq.getURI().toString();
            }
            return host.toURI() + httpReq.getURI();
        }
        
        // if not a friendly type, doing in a more generic way
        RequestLine reqLine = originalReq.getRequestLine();
        if (reqLine != null) {
            return reqLine.getUri();
        }
        return null;
    }
    
    //TODO is there value in moving this method to somewhere re-usable?
    private String resolveRedirectURL(
            final String redirectURL, final String nonAsciiCharset) {
        
        String url = redirectURL;

        // Is string containing only ASCII as it should?
        boolean isAscii = true;
        final int length = url.length();
        for (int offset = 0; offset < length; ) {
           final int codepoint = url.codePointAt(offset);
           if (codepoint > ASCII_MAX_CODEPOINT) {
               isAscii = false;
               break;
           }
           offset += Character.charCount(codepoint);
        }
        if (isAscii) {
            return url;
        } else {
            LOG.warn("Redirect URI made of 7-bit clean ASCII. "
                    + "It probably is not encoded properly. "
                    + "Will try to fix. Redirect URL: " + redirectURL);
        }
        
        // try to fix if non ascii charset is non UTF8.
        if (StringUtils.isNotBlank(nonAsciiCharset)) {
            String charset = CharsetUtils.clean(nonAsciiCharset);
            if (!CharEncoding.UTF_8.equals(charset)) {
                try {
                    url = new String(url.getBytes(charset));
                    return url;
                } catch (UnsupportedEncodingException e) {
                    LOG.warn("Could not fix badly encoded URL with charset \""
                            + charset + "\". Redirect URL: " + redirectURL, e);
                }
            }
        }
        
        // If all fails, fall back to UTF8
        try {
            url = new String(url.getBytes(CharEncoding.UTF_8));
            return url;
        } catch (UnsupportedEncodingException e) {
            LOG.warn("Could not fix badly encoded URL with charset "
                    + "\"UTF-8\". Redirect URL: " + redirectURL, e);
        }
        return url;
    }
    
    @Override
    public void loadFromXML(Reader in) {
        XMLConfiguration xml = ConfigurationUtil.newXMLConfiguration(in);
        setFallbackCharset(
                xml.getString("[@fallbackCharset]", getFallbackCharset()));
    }
    @Override
    public void saveToXML(Writer out) throws IOException {
        try {
            EnhancedXMLStreamWriter writer = new EnhancedXMLStreamWriter(out);
            writer.writeStartElement("redirectURLProvider");
            writer.writeAttribute("class", getClass().getCanonicalName());

            writer.writeAttribute("fallbackCharset", getFallbackCharset());
            
            writer.writeEndElement();
            
            writer.flush();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IOException("Cannot save as XML.", e);
        }
    }
    
    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
            .append("fallbackCharset", fallbackCharset)
            .toString();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
            .append(fallbackCharset)
            .toHashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof GenericRedirectURLProvider)) {
            return false;
        }
        GenericRedirectURLProvider other = (GenericRedirectURLProvider) obj;
        return new EqualsBuilder()
            .append(fallbackCharset, other.fallbackCharset)
            .isEquals();
    }
}
