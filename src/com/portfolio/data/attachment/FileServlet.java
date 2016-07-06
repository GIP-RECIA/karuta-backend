/* =======================================================
	Copyright 2014 - ePortfolium - Licensed under the
	Educational Community License, Version 2.0 (the "License"); you may
	not use this file except in compliance with the License. You may
	obtain a copy of the License at

	http://www.osedu.org/licenses/ECL-2.0

	Unless required by applicable law or agreed to in writing,
	software distributed under the License is distributed on an "AS IS"
	BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
	or implied. See the License for the specific language governing
	permissions and limitations under the License.
   ======================================================= */

package com.portfolio.data.attachment;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.naming.InitialContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;
import javax.ws.rs.core.Response.Status;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.InputSource;

import com.google.gson.stream.JsonWriter;
import com.portfolio.data.provider.DataProvider;
import com.portfolio.data.utils.ConfigUtils;
import com.portfolio.data.utils.SqlUtils;
import com.portfolio.rest.RestWebApplicationException;
import com.portfolio.security.Credential;

public class FileServlet  extends HttpServlet
{
	final Logger logger = LoggerFactory.getLogger(FileServlet.class);

//	DataProvider dataProvider = null;
	boolean hasNodeReadRight = false;
	boolean hasNodeWriteRight = false;
	final Credential credential = new Credential();

	private String server = "";
	private String backend = "";
	private String dataProviderName = "";
	ServletContext servContext;
	DataSource ds;
	ArrayList<String> ourIPs = new ArrayList<String>();
	DataProvider dataProvider;

	@Override
	public void init( ServletConfig config )
	{
		/// List possible local address
		try
		{
			super.init(config);
			
			dataProvider = SqlUtils.initProvider(config.getServletContext(), logger);
			
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces.hasMoreElements()){
				NetworkInterface current = interfaces.nextElement();
				if ( !current.isUp() ) continue;
				Enumeration<InetAddress> addresses = current.getInetAddresses();
				while (addresses.hasMoreElements()){
					InetAddress current_addr = addresses.nextElement();
					if (current_addr instanceof Inet4Address)
					{
						String ip = current_addr.getHostAddress();
//						System.out.println("USED IP: "+ip);
						ourIPs.add(ip);
					}
				}
			}
			// Force localhost ip to be set, sometime it isn't listed
//			ourIPs.add("127.0.0.1");
		}
		catch( Exception e )
		{
		}

//		servContext = config.getServletContext();
		servContext = config.getServletContext();
		try
		{
			ConfigUtils.loadConfigFile(config.getServletContext());
		}
		catch( Exception e1 )
		{
			e1.printStackTrace();
		}
		backend = ConfigUtils.get("backendserver");
		server = ConfigUtils.get("fileserver");
		dataProviderName = ConfigUtils.get("dataProviderClass");

		try
		{
			InitialContext cxt = new InitialContext();
			if ( cxt == null ) {
				throw new Exception("no context found!");
			}

			/// Init this here, might fail depending on server hosting
			ds = (DataSource) cxt.lookup( "java:/comp/env/jdbc/portfolio-backend" );
			if ( ds == null ) {
				throw new Exception("Data  jdbc/portfolio-backend source not found!");
			}
		}
		catch( Exception e )
		{
			logger.error(e.getMessage());
			e.printStackTrace();
		}
	}

	public void initialize(HttpServletRequest httpServletRequest)
	{
			//		  checkCredential(httpServletRequest);
	}

	@Override
	protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		doPost(request, response);
	}

	// =====================================================================================
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		// =====================================================================================
		initialize(request);

		String useragent = request.getHeader("User-Agent");
		logger.error("Agent: "+useragent);

//		DataProvider dataProvider = null;
		Connection c = null;
		try
		{
//			dataProvider = SqlUtils.initProvider(getServletContext(), logger);
//			dataProvider = (DataProvider) Class.forName(dataProviderName).newInstance();
			//On initialise le dataProvider
			/*
			if( ds == null )	// Case where we can't deploy context.xml
			{ c = SqlUtils.getConnection(servContext); }
			else
			{ c = ds.getConnection(); }
			dataProvider.setConnection(c);
			//*/
			c = SqlUtils.getConnection(getServletContext());

			int userId = 0;
			int groupId = 0;
			String user = "";
			boolean fromSakai = false;
	
			String doCopy = request.getParameter("copy");
			if( doCopy != null )
				doCopy = "?copy";
			else
				doCopy = "";
	
			HttpSession session = request.getSession(false);
			if( session != null )
			{
				String srceType = request.getParameter("srce");
				if( "sakai".equals(srceType) )
				{
					fromSakai = true;
				}
	
				Integer val = (Integer) session.getAttribute("uid");
				if( val != null )
					userId = val;
				val = (Integer) session.getAttribute("gid");
				if( val != null )
					groupId = val;
				user = (String) session.getAttribute("user");
			}
			else
			{
				response.sendError(HttpServletResponse.SC_FORBIDDEN);
				return;
			}
	
			/// uuid: celui de la ressource
			/// /resources/resource/file/{uuid}[?size=[S|L]&lang=[fr|en]]
	
			String origin = request.getRequestURL().toString();
	
			/// R�cup�ration des param�tres
			String url = request.getPathInfo();
			String[] token = url.split("/");
			String uuid = token[1];
	
			String size = request.getParameter("size");
			if(size == null)
				size = "S";
	
			String lang = request.getParameter("lang");
			if (lang==null){
				lang = "fr";
			}
	
			/// V�rification des droits d'acc�s
			if(!credential.hasNodeRight(c, userId, groupId, uuid, Credential.WRITE))
			{
				response.sendError(HttpServletResponse.SC_FORBIDDEN);
				//throw new Exception("L'utilisateur userId="+userId+" n'a pas le droit WRITE sur le noeud "+nodeUuid);
			}
	
			String data;
			String fileid = "";
			
			data = dataProvider.getResNode(c, uuid, userId, groupId);

			/// Parse les donn�es
			DocumentBuilderFactory documentBuilderFactory =DocumentBuilderFactory.newInstance();
			DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
			InputSource is = new InputSource(new StringReader("<node>"+data+"</node>"));
			Document doc = documentBuilder.parse(is);
			DOMImplementationLS impl = (DOMImplementationLS)doc.getImplementation().getFeature("LS", "3.0");
			LSSerializer serial = impl.createLSSerializer();
			serial.getDomConfig().setParameter("xml-declaration", false);

			/// Cherche si on a d�j� envoy� quelque chose
			XPath xPath = XPathFactory.newInstance().newXPath();
//			String filterRes = "//filename[@lang=\""+lang+"\"]";
			String filterRes = "//*[local-name()='filename' and @lang='"+lang+"']";
			NodeList nodelist = (NodeList) xPath.compile(filterRes).evaluate(doc, XPathConstants.NODESET);

			String filename = "";
			if( nodelist.getLength() > 0 )
				filename = nodelist.item(0).getTextContent();

			/// Ignore replacing file, just consider them all new one
			/*
			if( !"".equals(filename) )
			{
				/// Already have one, per language
//				String filterId = "//fileid[@lang='"+lang+"']";
				String filterId = "//*[local-name()='fileid' and @lang='"+lang+"']";
				NodeList idlist = (NodeList) xPath.compile(filterId).evaluate(doc, XPathConstants.NODESET);
				if( idlist.getLength() != 0 )
				{
					Element fileNode = (Element) idlist.item(0);
					fileid = fileNode.getTextContent();
				}
			}
	
			int last = fileid.lastIndexOf("/") +1;	// FIXME temp patch
			if( last < 0 )
				last = 0;
			fileid = fileid.substring(last);
			//*/
	
			/// �criture des donn�es
			String urlTarget = server + "/" + fileid+doCopy;
	//		String urlTarget = "http://"+ server + "/user/" + user +"/file/" + uuid +"/"+ lang+ "/ptype/fs";
	
			// Unpack form, fetch binary data and send
		// Create a factory for disk-based file items
			DiskFileItemFactory factory = new DiskFileItemFactory();
	
			// Create a new file upload handler
			ServletFileUpload upload = new ServletFileUpload(factory);
	
			String json = "";
			HttpURLConnection connection=null;
			// Parse the request
			InputStream inputData = null;
			String fileName = "";
			long filesize = 0;
			String contentType = "";

			if( fromSakai )
			{
				String sakai_session = (String) session.getAttribute("sakai_session");
				String sakai_server = (String) session.getAttribute("sakai_server");	// Base server http://localhost:9090
				String srceUrl = request.getParameter("srceurl");

				HttpClient client = new HttpClient();

				// Create connection to url
				GetMethod get = new GetMethod(sakai_server+"/"+srceUrl);
				// Set headers
				Header header = new Header();
				header.setName("JSESSIONID");
				header.setValue(sakai_session);
				get.setRequestHeader(header);

				int status = client.executeMethod(get);
				if (status != HttpStatus.SC_OK) {
					System.err.println("Method failed: " + get.getStatusLine());
				}

				// Retrieve inputData
				inputData = get.getResponseBodyAsStream();
				// File detail
				Header nameHeader = get.getResponseHeader("Content-Disposition");
				Header sizeHeader = get.getResponseHeader("Content-Length");
				Header typeHeader = get.getResponseHeader("Content-Type");

				filesize = Integer.parseInt(sizeHeader.getValue());
				contentType = typeHeader.getValue();
				fileName = nameHeader.getValue().split("=")[1];
				if( fileName.startsWith("\"") )
					fileName = fileName.substring(1, fileName.length()-1);
			}
			else
			{
//				if( ServletFileUpload.isMultipartContent(request) )
				if( true )
				{
					List<FileItem> items = upload.parseRequest(request);
					// Process the uploaded items
					Iterator<FileItem> iter = items.iterator();
					while (iter.hasNext())
					{
						FileItem item = iter.next();

						if ("uploadfile".equals(item.getFieldName()))
						{
							// Send raw data
							inputData = item.getInputStream();

							fileName = item.getName();
							filesize = item.getSize();
							contentType = item.getContentType();

							break;
						}
					}
				}
				else
				{
					// List headers
					Enumeration attributes = request.getAttributeNames();
					while( attributes.hasMoreElements() )
					{
						Object elem = attributes.nextElement();
						logger.error("Object: "+elem.toString());
					}
					logger.error("Not multipart");
				}
			}

			if( inputData != null )
			{
				connection = CreateConnection( urlTarget, request );
				connection.setRequestProperty("filename",uuid);
				connection.setRequestProperty("content-type", "application/octet-stream");
				connection.setRequestProperty("content-length", Long.toString(filesize));
				connection.connect();

				/// Send data to fileserver
				OutputStream outputData = connection.getOutputStream();
				IOUtils.copy(inputData, outputData);

				/// Those 2 lines are needed, otherwise, no request sent
				int code = connection.getResponseCode();
				String msg = connection.getResponseMessage();

				/// Retrieving info
				InputStream objReturn = connection.getInputStream();
				StringWriter idResponse = new StringWriter();
				IOUtils.copy(objReturn, idResponse);
				fileid = idResponse.toString();

				connection.disconnect();

				/// Construct Json
				StringWriter StringOutput = new StringWriter();
				JsonWriter writer = new JsonWriter(StringOutput);
				writer.beginObject();
				writer.name("files");
				writer.beginArray();
				writer.beginObject();

				writer.name("name").value(fileName);
				writer.name("size").value(filesize);
				writer.name("type").value(contentType);
				writer.name("url").value(origin);
				writer.name("fileid").value(fileid);
				//                               writer.name("deleteUrl").value(ref);
				//                                       writer.name("deleteType").value("DELETE");
				writer.endObject();

				writer.endArray();
				writer.endObject();

				writer.close();

				json = StringOutput.toString();
			}
	
			connection.disconnect();
			/// Renvoie le JSON au client
			if( useragent.contains("MSIE 9.0") || useragent.contains("MSIE 8.0") || useragent.contains("MSIE 7.0") )
				response.setContentType("text/html");
			else	// The normal type
				response.setContentType("application/json");
			PrintWriter respWriter = response.getWriter();
			respWriter.write(json);

//		RetrieveAnswer(connection, response, ref);
//		dataProvider.disconnect();
		}
		catch(Exception e)
		{
			logger.error("Binary transfer error: "+e.getMessage()+"");
			e.printStackTrace();
		}
		finally
		{
			try
			{
				if( c != null ) c.close();
			}
			catch( SQLException e ){ e.printStackTrace(); }
		}
	}

	// =====================================================================================
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		initialize(request);

//		DataProvider dataProvider = null;
		Connection c = null;
		try{
//			dataProvider = SqlUtils.initProvider(getServletContext(), logger);
			c = SqlUtils.getConnection(getServletContext());
			/*
			dataProvider = (DataProvider) Class.forName(dataProviderName).newInstance();
			//On initialise le dataProvider
			if( ds == null )	// Case where we can't deploy context.xml
			{ c = SqlUtils.getConnection(servContext); }
			else
			{ c = ds.getConnection(); }
			dataProvider.setConnection(c);
			//*/

			int userId = 0;
			int groupId = 0;
			String user = "";
			String context = request.getContextPath();
			String url = request.getPathInfo();
	
			HttpSession session = request.getSession(true);
			if( session != null )
			{
				Integer val = (Integer) session.getAttribute("uid");
				if( val != null )
					userId = val;
				val = (Integer) session.getAttribute("gid");
				if( val != null )
					groupId = val;
				user = (String) session.getAttribute("user");
			}
	
			/*
			Credential credential = null;
			try
			{
				//On initialise le dataProvider
				Connection c = null;
				if( ds == null )	// Case where we can't deploy context.xml
				{ c = SqlUtils.getConnection(servContext); }
				else
				{ c = ds.getConnection(); }
				dataProvider.setConnection(c);
				credential = new Credential(c);
			}
			catch(Exception e)
			{
				e.printStackTrace();
			}
			//*/
	
			// =====================================================================================
			boolean trace = false;
			StringBuffer outTrace = new StringBuffer();
			StringBuffer outPrint = new StringBuffer();
			String logFName = null;
	
			response.setCharacterEncoding("UTF-8");
	
			System.out.println("FileServlet::doGet: "+url+" from user: "+userId );
			// ====== URI : /resources/file[/{lang}]/{context-id}
			// ====== PathInfo: /resources/file[/{uuid}?lang={fr|en}&size={S|L}] pathInfo
			//			String uri = request.getRequestURI();
			String[] token = url.split("/");
			String uuid = token[1];
			//wadbackend.WadUtilities.appendlogfile(logFName, "GETfile:"+request.getRemoteAddr()+":"+uri);

			/// FIXME: Passe la s�curit� si la source provient de localhost, il faudrait un �change afin de s'assurer que n'importe quel servlet ne puisse y acc�der
			String sourceip = request.getRemoteAddr();
//			System.out.println("IP: "+sourceip);
//			System.out.println(ourIPs);

			/// V�rification des droits d'acc�s
			// TODO: Might be something special with proxy and export/PDF, to investigate

			if( !ourIPs.contains(sourceip) )
			{
				if( userId == 0 )
					throw new RestWebApplicationException(Status.FORBIDDEN, "");

				if(!credential.hasNodeRight(c, userId, groupId, uuid, Credential.READ))
				{
					response.sendError(HttpServletResponse.SC_FORBIDDEN);
					//throw new Exception("L'utilisateur userId="+userId+" n'a pas le droit READ sur le noeud "+nodeUuid);
				}
			}
			else	// Si la requ�te est locale et qu'il n'y a pas de session, on ignore la v�rification
			{
				System.out.println("IP OK: bypass");
				logger.error("IP OK: bypass");
			}

			/// On r�cup�re le noeud de la ressource pour retrouver le lien
			String data = dataProvider.getResNode(c, uuid, userId, groupId);

			//			javax.servlet.http.HttpSession session = request.getSession(true);
			//====================================================
			//String ppath = session.getServletContext().getRealPath("/");
			//logFName = ppath +"logs/logNode.txt";
			//====================================================
			String size = request.getParameter("size");
			if(size == null)
				size = "";

			String lang = request.getParameter("lang");
			if (lang==null){
				lang = "fr";
			}

			/*
			String nodeUuid = uri.substring(uri.lastIndexOf("/")+1);
			if  (uri.lastIndexOf("/")>uri.indexOf("file/")+6) { // -- file/ = 5 carac. --
				lang = uri.substring(uri.indexOf("file/")+5,uri.lastIndexOf("/"));
			}
			//*/

			String ref = request.getHeader("referer");

			/// Parse les donn�es
			DocumentBuilderFactory documentBuilderFactory =DocumentBuilderFactory.newInstance();
			DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
			InputSource is = new InputSource(new StringReader("<node>"+data+"</node>"));
			Document doc = documentBuilder.parse(is);
			DOMImplementationLS impl = (DOMImplementationLS)doc.getImplementation().getFeature("LS", "3.0");
			LSSerializer serial = impl.createLSSerializer();
			serial.getDomConfig().setParameter("xml-declaration", false);

			/// Trouve le bon noeud
			XPath xPath = XPathFactory.newInstance().newXPath();

			/// Either we have a fileid per language
//			String filterRes = "//fileid[@lang='"+lang+"']";
			String filterRes = "//*[local-name()='fileid' and @lang='"+lang+"']";
			NodeList nodelist = (NodeList) xPath.compile(filterRes).evaluate(doc, XPathConstants.NODESET);
			String resolve = "";
			if( nodelist.getLength() != 0 )
			{
				Element fileNode = (Element) nodelist.item(0);
				resolve = fileNode.getTextContent();
			}

			/// Or just a single one shared
			if( "".equals(resolve) )
			{
				response.setStatus(404);
				response.getOutputStream().close();
				return;
			}

//			String filterName = "//filename[@lang='"+lang+"']";
			String filterName = "//*[local-name()='filename' and @lang='"+lang+"']";
			NodeList textList = (NodeList) xPath.compile(filterName).evaluate(doc, XPathConstants.NODESET);
			String filename = "";
			if( textList.getLength() != 0 )
			{
				Element fileNode = (Element) textList.item(0);
				filename = fileNode.getTextContent();
			}

//			String filterType = "//type[@lang='"+lang+"']";
			String filterType = "//*[local-name()='type' and @lang='"+lang+"']";
			textList = (NodeList) xPath.compile(filterType).evaluate(doc, XPathConstants.NODESET);
			String type = "";
			if( textList.getLength() != 0 )
			{
				Element fileNode = (Element) textList.item(0);
				type = fileNode.getTextContent();
			}

			/*
			String filterSize = "//size[@lang='"+lang+"']";
			textList = (NodeList) xPath.compile(filterName).evaluate(doc, XPathConstants.NODESET);
			String filesize = "";
			if( textList.getLength() != 0 )
			{
				Element fileNode = (Element) textList.item(0);
				filesize = fileNode.getTextContent();
			}
			//*/

			System.out.println("!!! RESOLVE: "+resolve);

			/// Envoie de la requ�te au servlet de fichiers
			// http://localhost:8080/MiniRestFileServer/user/claudecoulombe/file/a8e0f07f-671c-4f6a-be6c-9dba12c519cf/ptype/sql
			/// TODO: Ne plus avoir besoin du switch
			String urlTarget = server + "/" + resolve;
			
			if( "T".equals(size) )
				urlTarget = urlTarget + "/thumb";
//			String urlTarget = "http://"+ server + "/user/" + resolve +"/"+ lang + "/ptype/fs";

			HttpURLConnection connection = CreateConnection( urlTarget, request );
			connection.connect();
			InputStream input = connection.getInputStream();
			String sizeComplete = connection.getHeaderField("Content-Length");
			int completeSize = Integer.parseInt(sizeComplete);

			response.setContentLength(completeSize);
			response.setContentType(type);
			response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
			ServletOutputStream output = response.getOutputStream();

			byte[] buffer = new byte[0x100000];
			int totalRead = 0;
			int bytesRead = -1;

			while ((bytesRead = input.read(buffer,0,0x100000)) != -1 || totalRead < completeSize) {
				output.write(buffer, 0, bytesRead);
				totalRead += bytesRead;
			}

//			IOUtils.copy(input, output);
//			IOUtils.closeQuietly(output);

			output.flush();
			output.close();
			input.close();
			connection.disconnect();
		}
		catch( RestWebApplicationException e )
		{
			logger.error(e.getMessage());
			e.printStackTrace();
		}
		catch(Exception e){
			logger.error(e.getMessage());
			logger.error(e.toString()+" -> "+e.getLocalizedMessage());
			e.printStackTrace();
			//wadbackend.WadUtilities.appendlogfile(logFName, "GETfile: error"+e);
		}
		finally
		{
			try
			{
				if( c != null ) c.close();
			}
			catch(Exception e){
				ServletOutputStream out = response.getOutputStream();
				out.println("Erreur dans doGet: " +e);
				out.close();
			}
//				dataProvider.disconnect();
			request.getInputStream().close();
			response.getOutputStream().close();
		}
	}

	HttpURLConnection CreateConnection( String url, HttpServletRequest request ) throws MalformedURLException, IOException
	{
		/// Create connection
		URL urlConn = new URL(url);
		HttpURLConnection connection = (HttpURLConnection) urlConn.openConnection();
		connection.setDoOutput(true);
		connection.setUseCaches(false);                 /// We don't want to cache data
		connection.setInstanceFollowRedirects(false);   /// Let client follow any redirection
		String method = request.getMethod();
		connection.setRequestMethod(method);

		String context = request.getContextPath();
		connection.setRequestProperty("app", context);

		/// Transfer headers
		String key = "";
		String value = "";
		Enumeration<String> header = request.getHeaderNames();
		while( header.hasMoreElements() )
		{
			key = header.nextElement();
			value = request.getHeader(key);
			connection.setRequestProperty(key, value);
		}

		return connection;
	}

	void InitAnswer( HttpURLConnection connection, HttpServletResponse response, String referer ) throws MalformedURLException, IOException
	{
		String ref = null;
		if( referer != null )
		{
			int first = referer.indexOf('/', 7);
			int last = referer.lastIndexOf('/');
			ref = referer.substring(first, last);
		}

		response.setContentType(connection.getContentType());
		response.setStatus(connection.getResponseCode());
		response.setContentLength(connection.getContentLength());

		/// Transfer headers
		Map<String, List<String>> headers = connection.getHeaderFields();
		int size=headers.size();
		for( int i=1; i<size; ++i )
		{
			String key = connection.getHeaderFieldKey(i);
			String value = connection.getHeaderField(i);
			//	      response.setHeader(key, value);
			response.addHeader(key, value);
		}

		/// Deal with correct path with set cookie
		List<String> setValues = headers.get("Set-Cookie");
		if( setValues != null )
		{
			String setVal = setValues.get(0);
			int pathPlace = setVal.indexOf("Path=");
			if( pathPlace > 0 )
			{
				setVal = setVal.substring(0, pathPlace+5);  // Some assumption, may break
				setVal = setVal+ref;

				response.setHeader("Set-Cookie", setVal);
			}
		}
	}

	void RetrieveAnswer( HttpURLConnection connection, HttpServletResponse response, String referer ) throws MalformedURLException, IOException
	{
		/// Receive answer
		InputStream in;
		try
		{
			in = connection.getInputStream();
		}
		catch (Exception e)
		{
			System.out.println(e.toString());
			in = connection.getErrorStream();
		}

		InitAnswer(connection, response, referer);

		/// Write back data
		DataInputStream stream = new DataInputStream(in);
		byte[] buffer = new byte[1024];
		int size;
		ServletOutputStream out=null;
		try
		{
			out = response.getOutputStream();
			while( (size = stream.read(buffer,0,buffer.length)) != -1 )
				out.write(buffer, 0, size);

		}
		catch (Exception e)
		{
			System.out.println(e.toString());
			System.out.println("Writing messed up!");
		}
		finally
		{
			in.close();
			out.flush();  // close() should flush already, but Tomcat 5.5 doesn't
			out.close();
		}
	}

	// [username, ?]
	String[] processCookie( Cookie[] cookies )
	{
		String login=null;
		String[] ret = {login};
		if( cookies == null ) return ret;

		for( int i=0; i<cookies.length; ++i )
		{
			Cookie cookie = cookies[i];
			String name = cookie.getName();
			if( "user".equals(name) || "useridentifier".equals(name) )
				login = cookie.getValue();
		}

		ret[0] = login;
		return ret;
	}
}

