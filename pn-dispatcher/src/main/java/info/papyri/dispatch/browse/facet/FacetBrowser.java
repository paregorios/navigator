package info.papyri.dispatch.browse.facet;

import info.papyri.dispatch.browse.DocumentBrowseRecord;
import info.papyri.dispatch.browse.DocumentCollectionBrowseRecord;
import info.papyri.dispatch.browse.FieldNotFoundException;
import info.papyri.dispatch.browse.SolrField;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CommonsHttpSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;

@WebServlet(name = "FacetBrowser", urlPatterns = {"/FacetBrowser"})

/**
 * Enables faceted browsing of the pn collections
 * 
 * @author thill
 */

public class FacetBrowser extends HttpServlet {
    
    /** Solr server address. Supplied in config file */
    static String SOLR_URL;
    /** Path to appropriate Solr core */
    static String PN_SEARCH = "pn-search/"; 
    /** path to home html directory */
    static private String home;
    /** path to html file used in html injection */
    static private URL FACET_URL;
    /** path to servlet */
    /* TODO: Get this squared up with urlPatterns, above */
    static private String FACET_PATH = "/dispatch/faceted/";
    /** Number of records to show per page. Used in pagination */
    static private int documentsPerPage = 50;
    
    static String BASE;
    
    String debug;

    
    @Override
    public void init(ServletConfig config) throws ServletException{
        
        super.init(config);

        SOLR_URL = config.getInitParameter("solrUrl");
        home = config.getInitParameter("home");
        BASE = config.getInitParameter("htmlPath");
        try {
            
            FACET_URL = new URL("file://" + home + "/" + "facetbrowse.html");
            
        } catch (MalformedURLException e) {
      
            throw new ServletException(e);
   
        }
        
    } 
    
    
    /** 
     * Processes requests for both HTTP <code>GET</code> and <code>POST</code> methods.
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        debug = "";
        response.setContentType("text/html;charset=UTF-8");
        request.setCharacterEncoding("UTF-8");
        ArrayList<Facet> facets = getFacets();
        Boolean constraintsPresent = parseRequestToFacets(request, facets);
        int page = request.getParameter("page") != null ? Integer.valueOf(request.getParameter("page")) : 0;
        SolrQuery solrQuery = this.buildFacetQuery(page, facets);
        QueryResponse queryResponse = runFacetQuery(solrQuery);
        String origQuery = solrQuery.toString();
        passCompletedQueryToDateFacet(facets, solrQuery);
        long resultSize = queryResponse.getResults().getNumFound();
        populateFacets(facets, queryResponse);
        String currentQuery = solrQuery.toString();
        ArrayList<DocumentBrowseRecord> returnedRecords = retrieveRecords(queryResponse);
        String html = this.assembleHTML(facets, constraintsPresent, resultSize, returnedRecords, request.getParameterMap());
        displayBrowseResult(response, html);  
     
    }
    

    private ArrayList<Facet> getFacets(){
        
        
        ArrayList<Facet> facets = new ArrayList<Facet>();
        
        facets.add(new SubStringFacet());
        facets.add(new LanguageFacet());
        facets.add(new PlaceFacet());
        facets.add(new DateFacet());
        facets.add(new HasImagesFacet());
        facets.add(new HasTranscriptionFacet());
        facets.add(new TranslationFacet());
        
        return facets;
        
    }
  
    Boolean parseRequestToFacets(HttpServletRequest request, ArrayList<Facet> facets){
                
        Map<String, String[]> requestParams = request.getParameterMap();
        Boolean constraintsPresent = false;
                    
        Iterator<Facet> fit = facets.iterator();
        
        while(fit.hasNext()){
            
            Facet facet = fit.next();
            if(facet.addConstraints(requestParams)) constraintsPresent = true;
            
        }
        
        return constraintsPresent;
               
    }
    
    /**
     * Generates the <code>SolrQuery</code> to be used to retrieve <code>Record</code>s and to 
     * populate the <code>Facet</code>s.
     * 
     * @param pageNumber
     * @param paramsToFacets
     * @return The <code>SolrQuery</code>
     * @see Facet#buildQueryContribution(org.apache.solr.client.solrj.SolrQuery) 
     */
    
    SolrQuery buildFacetQuery(int pageNumber, ArrayList<Facet> facets){
        
        SolrQuery sq = new SolrQuery();
        sq.setFacetMissing(true);
        sq.setRows(documentsPerPage); 
        sq.setStart(pageNumber * documentsPerPage); 
        
        // iterate through facets, adding to solr query
        Iterator<Facet> fit = facets.iterator();
        while(fit.hasNext()){
            
            Facet facet = fit.next();
            sq = facet.buildQueryContribution(sq);
            
            
        }
        // the Facets all add FilterQueries to the SolrQuery. We want all documents
        // that pass these filters.
        sq.setQuery("*:*");
        
        return sq;
        
        
    }
    
    /**
     * Queries the Solr server.
     * 
     * 
     * @param sq
     * @return The <code>QueryResponse</code> returned by the Solr server
     */
    
    static QueryResponse runFacetQuery(SolrQuery sq){
        
        try{
            
          SolrServer solrServer = new CommonsHttpSolrServer(SOLR_URL + PN_SEARCH);
          QueryResponse qr = solrServer.query(sq);
          return qr;
            
            
        }
        catch(MalformedURLException murle){
            
            System.out.println("MalformedURLException at info.papyri.dispatch.browse.facet.FacetBrowser: " + murle.getMessage());
            return null;
            
            
        }
        catch(SolrServerException sse){
            
           System.out.println("SolrServerException at info.papyri.dispatch.browse.facet.FacetBrowser: " + sse.getMessage());
           return null;
            
            
        }
        
        
    }
    
    /**
     * Sends the <code>QueryResponse</code> returned by the Solr server to each of the
     * <code>Facet</code>s to populate its values list.
     * 
     * 
     * @param paramsToFacets
     * @param queryResponse 
     * @see Facet#setWidgetValues(org.apache.solr.client.solrj.response.QueryResponse) 
     */
    
    private void populateFacets(ArrayList<Facet> facets, QueryResponse queryResponse){
        
        Iterator<Facet> fit = facets.iterator();
        while(fit.hasNext()){
            
            Facet facet = fit.next();
            facet.setWidgetValues(queryResponse);
                      
        }
        
    }
    
    /**
     * Parses the <code>QueryResponse</code> returned by the Solr server into a list of
     * <code>DocumentBrowseRecord</code>s.
     * 
     * 
     * @param queryResponse
     * @return An <code>ArrayList</code> of <code>DocumentBrowseRecord</code>s.
     * @see DocumentBrowseRecord
     */
    
    private ArrayList<DocumentBrowseRecord> retrieveRecords(QueryResponse queryResponse){
        
        ArrayList<DocumentBrowseRecord> records = new ArrayList<DocumentBrowseRecord>();
        
        for(SolrDocument doc : queryResponse.getResults()){
            
           try{ 
            
                DocumentCollectionBrowseRecord collectionInfo = getDisplayCollectionInfo(doc);
                String displayId = getDisplayId(collectionInfo, doc);
                URL url = new URL((String) doc.getFieldValue(SolrField.id.name()));
                Boolean placeIsNull = doc.getFieldValue(SolrField.display_place.name()) == null;
                String place = placeIsNull ? "Not recorded" : (String) doc.getFieldValue(SolrField.display_place.name());
                Boolean dateIsNull = doc.getFieldValue(SolrField.display_date.name()) == null;
                String date = dateIsNull ? "Not recorded" : (String) doc.getFieldValue(SolrField.display_date.name());
                Boolean languageIsNull = doc.getFieldValue(SolrField.language.name()) == null;
                String language = languageIsNull ? "Not recorded" : (String) doc.getFieldValue(SolrField.language.name()).toString().replaceAll("[\\[\\]]", "");
                Boolean noTranslationLanguages = doc.getFieldValue(SolrField.translation_language.name()) == null;
                String translationLanguages = noTranslationLanguages ? "No translation" : (String)doc.getFieldValue(SolrField.translation_language.name()).toString().replaceAll("[\\[\\]]", "");
                Boolean hasImages = doc.getFieldValuesMap().containsKey(SolrField.images.name()) && (Boolean)doc.getFieldValue(SolrField.images.name()) ? true : false;            
                String invNum = (String)doc.getFieldValue(SolrField.invnum.name());
                DocumentBrowseRecord record;           
                record = new DocumentBrowseRecord(collectionInfo, displayId, url, place, date, language, hasImages, translationLanguages, invNum);
                records.add(record);
                
           }
           catch (FieldNotFoundException fnfe){
               
               System.out.println(fnfe.getError());
               continue;
           }
           catch (MalformedURLException mue){
               
               System.out.print("Malformed URL: " + mue.getMessage());
               
           }
        }
        
        // TODO: need to add code for  - retrieving all ids; getting which one to display as head id; retrieving url; parsing into Record
        
        return records;
        
    }
    
    /**
     * Builds a <code>DocumentCollectionBrowseRecord</code> for the passed <code>SolrDocument</code>.
     * 
     * This method will need to be worked on, as right now it's pretty arbitrary: the record generated is based on:
     * (1) The first ddbdp info encountered
     * (2) Failing that, the first hgv info encountered
     * (3) Failing that, the first apis info encountered
     * 
     * @param doc
     * @return
     * @throws FieldNotFoundException 
     * @see DocumentCollectionBrowseRecord
     */

    private DocumentCollectionBrowseRecord getDisplayCollectionInfo(SolrDocument doc) throws FieldNotFoundException{
        
        // TODO: this is just a bodge, pending feedback on what the preferred ids are
        
        ArrayList<String> collections = new ArrayList<String>(Arrays.asList(doc.getFieldValue("collection").toString().replaceAll("[\\[\\]]", "").split(",")));           
        if(collections.isEmpty()) throw new FieldNotFoundException("collection");
        String collection = collections.contains("ddbdp") ? "ddbdp" : (collections.contains("hgv")? "hgv" : "apis");
        String collectionPrefix = collection + "_";
        ArrayList<String> series = new ArrayList<String>(Arrays.asList(doc.getFieldValue(collectionPrefix + SolrField.series.name()).toString().replaceAll("[\\[\\]]", "").split(",")));
        ArrayList<String> volumes = new ArrayList<String>(Arrays.asList(doc.getFieldValue(collectionPrefix + SolrField.volume.name()).toString().replaceAll("[\\[\\]]", "").split(",")));
        ArrayList<String> itemIds = new ArrayList<String>(Arrays.asList(doc.getFieldValue(collectionPrefix + SolrField.item.name()).toString().replaceAll("[\\[\\]]", "").split(",")));
        String errInfo = collections.toString() + "|" + volumes.toString() + "|" + itemIds.toString();
        if(series.isEmpty()) throw new FieldNotFoundException(collectionPrefix + "series", errInfo);    
        if(itemIds.isEmpty()) throw new FieldNotFoundException(collectionPrefix + "item", errInfo);

        if(volumes.size() > 0){
            
            return new DocumentCollectionBrowseRecord(collection, series.get(0), volumes.get(0));
            
        }
        else{
            
            return new DocumentCollectionBrowseRecord(collection, series.get(0), false);
            
        }
         
    }
    
    
    /**
     * Determines the collection/series/volume-specific id number to be displayed for the passed
     * <code>SolrDocument</code>, based on its associated <code>DocumentCollectionBrowseRecord</code>
     * 
     * This method will need to be worked on; it's utterly dependent upon #getDisplayCollectionInfo, which is
     * right now pretty arbitrary in its operations
     * 
     * @param collectionInfo
     * @param doc
     * @return 
     */
    
    private String getDisplayId(DocumentCollectionBrowseRecord collectionInfo, SolrDocument doc){
        
        // TODO: just a bodge; see getDisplayCollectionInfo above
        
        String collectionPrefix = collectionInfo.getCollection() + "_";
        ArrayList<String> itemIds = new ArrayList<String>(Arrays.asList(doc.getFieldValue(collectionPrefix + SolrField.item.name()).toString().replaceAll("[\\[\\]]", "").split(",")));     
        return itemIds.get(0);
        
    }
    
    /**
     * Generates the HTML for injection
     * 
     * 
     * @param paramsToFacets
     * @param constraintsPresent
     * @param resultsSize
     * @param returnedRecords
     * @param solrQuery Used for cheap 'n' easy debugging only
     * @return 
     */
    
    private String assembleHTML(ArrayList<Facet> facets, Boolean constraintsPresent, long resultsSize, ArrayList<DocumentBrowseRecord> returnedRecords, Map<String, String[]> submittedParams){
        
        StringBuilder html = new StringBuilder("<div id=\"facet-wrapper\">");
        assembleWidgetHTML(facets, html, submittedParams);
        html.append("<div id=\"vals-and-records-wrapper\">");
        if(constraintsPresent) assemblePreviousValuesHTML(facets,html, submittedParams);
        assembleRecordsHTML(facets, returnedRecords, constraintsPresent, resultsSize, html);
        html.append("</div><!-- closing #vals-and-records-wrapper -->");
        html.append("</div><!-- closing #facet-wrapper -->");
        return html.toString();
        
    }
    
    /**
     * Assembles the HTML displaying the <code>Facet</code> control widgets
     * 
     * 
     * @param paramsToFacets
     * @param html
     * @return 
     * @see Facet#generateWidget() 
     */
  
    private StringBuilder assembleWidgetHTML(ArrayList<Facet> facets, StringBuilder html, Map<String, String[]> submittedParams){
        
        html.append("<div id=\"facet-widgets-wrapper\">");
        html.append("<form name=\"facets\" method=\"get\" action=\"");
        html.append(FACET_PATH);
        html.append("\"> ");
        Iterator<Facet> fit = facets.iterator();
        while(fit.hasNext()){
            
            Facet facet = fit.next();
            html.append(facet.generateWidget());
            
        }
        html.append("<input type=\"submit\"/>");
        html.append("</form>");
        html.append("</div><!-- closing #facet-widgets-wrapper -->");
        return html;
        
    }
    
    /**
     * Assembles the HTML displaying the records returned by the Solr server
     * 
     * 
     * @param paramsToFacets
     * @param returnedRecords
     * @param constraintsPresent
     * @param resultSize
     * @param html
     * @param sq Used in debugging only
     * @return 
     * @see DocumentBrowseRecord
     */
    
    private StringBuilder assembleRecordsHTML(ArrayList<Facet> facets, ArrayList<DocumentBrowseRecord> returnedRecords, Boolean constraintsPresent, long resultSize, StringBuilder html){
        
        html.append("<div id=\"facet-records-wrapper\">");
        if(!constraintsPresent){
            
            html.append("<h2>Please select values from the left-hand column to return results</h2>");
            
        }
        else if(resultSize == 0){
            
            html.append("<h2>0 documents found matching criteria set.</h2>");
            html.append("<p>To determine why this is, try setting your criteria one at a time to see how this affects the results returned.</p>");
            
        }
        else{
            
            html.append("<table>");
            html.append("<tr class=\"tablehead\"><td>Identifier</td><td>Location</td><td>Date</td><td>Languages</td><td>Translation</td><td>Has images</td></tr>");
            Iterator<DocumentBrowseRecord> rit = returnedRecords.iterator();
            
            while(rit.hasNext()){
            
                DocumentBrowseRecord dbr = rit.next();
                html.append(dbr.getHTML());
                  
            }
            html.append("</table>");
            html.append(doPagination(facets, resultSize));
        }
        
        html.append("</div><!-- closing #facet-records-wrapper -->");
        return html;
          
    }
    
    /**
     * Generates the HTML controls indicating the constraints currently set on each <code>Facet</code>, and that,
     * when clicked, remove the constraint which they designate.
     * 
     * 
     * @param paramsToFacets
     * @param html
     * @return 
     * @see #buildFilteredQueryString(java.util.EnumMap, info.papyri.dispatch.browse.facet.FacetParam, java.lang.String) 
     */
    
    private StringBuilder assemblePreviousValuesHTML(ArrayList<Facet> facets, StringBuilder html, Map<String, String[]> submittedParams){
                
        html.append("<div id=\"previous-values\">");
        
        Iterator<Facet> fit = facets.iterator();
        
        while(fit.hasNext()){
            
            Facet facet = fit.next();
            
            String[] params = facet.getFormNames();
            
            for(int i = 0; i < params.length; i++){
            
                String param = params[i];
                
                if(submittedParams.containsKey(param)){

                    String displayName = facet.getDisplayName(param);

                    ArrayList<String> facetValues = facet.getFacetConstraints(param);

                    Iterator<String> fvit = facetValues.iterator();

                    while(fvit.hasNext()){

                        String facetValue = fvit.next();
                        String displayFacetValue = facet.getDisplayValue(facetValue);
                        String queryString = this.buildFilteredQueryString(facets, facet, param, facetValue);
                        html.append("<div class=\"facet-constraint\">");
                        html.append("<div class=\"constraint-label\">");
                        html.append(displayName);
                        html.append(": ");
                        html.append(displayFacetValue);
                        html.append("</div><!-- closing .constraint-label -->");
                        html.append("<div class=\"constraint-closer\">");
                        html.append("<a href=\"");
                        html.append(FACET_PATH);
                        html.append("".equals(queryString) ? "" : "?");
                        html.append(queryString);
                        html.append("\" title =\"Remove facet value\">X</a>");
                        html.append("</div><!-- closing .constraint-closer -->");
                        html.append("<div class=\"spacer\"></div>");
                        html.append("</div><!-- closing .facet-constraint -->");
                    }

                }
            
            }
                     
        }
                
        html.append("<div class=\"spacer\"></div>");
        html.append("</div><!-- closing #previous-values -->");
        return html;
        
    }
    
    /**
     * Injects the HTML code previously generated by the <code>assembleHTML</code> method
     * 
     * @param response
     * @param html 
     * @see #assembleHTML(java.util.EnumMap, java.lang.Boolean, long, java.util.ArrayList, org.apache.solr.client.solrj.SolrQuery) 
     */
    
    void displayBrowseResult(HttpServletResponse response, String html){
        
        BufferedReader reader = null;
        try{
        
            PrintWriter out = response.getWriter();
            reader = new BufferedReader(new InputStreamReader(FACET_URL.openStream()));
            String line = "";
            while ((line = reader.readLine()) != null) {
              
                out.println(line);

                if (line.contains("<!-- Facet browse results -->")) {
            
                    out.println(html);
                
                
                }
          
            } 
        
        }
        catch(Exception e){
            
            
            
        }
        
    }
    
    /**
     * Does what it says.
     * 
     * The complicating factor is that the current query string needs to be regenerated for each link in order
     * to maintain state across the pages.
     * 
     * @param paramsToFacets
     * @param resultSize
     * @return 
     * @see #buildFullQueryString(java.util.EnumMap) 
     */
    
    private String doPagination(ArrayList<Facet> facets, long resultSize){
        
        int numPages = (int)(Math.ceil(resultSize / documentsPerPage));
        
        if(numPages <= 1) return "";
        
        String fullQueryString = buildFullQueryString(facets);
        
        double widthEach = 8;
        double totalWidth = widthEach * numPages;
        totalWidth = totalWidth > 100 ? 100 : totalWidth;
        
        StringBuilder html = new StringBuilder("<div id=\"pagination\" style=\"width:");
        html.append(String.valueOf(totalWidth));
        html.append("%\">");
        
        for(long i = 1; i <= numPages; i++){
            
            html.append("<div class=\"page\">");
            html.append("<a href=\"");
            html.append(FACET_PATH);
            html.append("?");
            html.append(fullQueryString);
            html.append("page=");
            html.append(i);
            html.append("\">");
            html.append(String.valueOf(i));
            html.append("</a>");
            html.append("</div><!-- closing .page -->");
            
        }
        html.append("<div class=\"spacer\"></div><!-- closing .spacer -->");
        html.append("</div><!-- closing #pagination -->");
        return html.toString();
        
    }
    
    /**
     * Concatenates the querystring portions retrieved from each <code>Facet</code> into a
     * complete querystring. 
     * 
     * @param paramsToFacets
     * @return 
     * @see Facet#getAsQueryString() 
     */
    
    private String buildFullQueryString(ArrayList<Facet> facets){
             
        ArrayList<String> queryStrings = new ArrayList<String>();
        
        Iterator<Facet> fit = facets.iterator();
        while(fit.hasNext()){
            
            Facet facet = fit.next();
            String queryString = facet.getAsQueryString();
            if(!"".equals(queryString)) queryStrings.add(queryString);
            
        }
        
        String fullQueryString = "";
        Iterator<String> qsit = queryStrings.iterator();
        while(qsit.hasNext()){
            
            fullQueryString += qsit.next();
            fullQueryString += "&";
            
        }

        return fullQueryString;
        
    }
    
    /**
     * Concatenates the querystring portions retrieved from each <code>Facet</code> into a
     * compelete querystring, with the exception of the value given in the <code>facetValue</code>
     * param in relation to the <code>Facet</code> derived from the <code>facetParam</code> and
     * <code>paramsToFacets</code> params.
     * 
     * 
     * @param paramsToFacets
     * @param facetParam
     * @param facetValue
     * @return 
     * @see Facet#getAsFilteredQueryString(java.lang.String) 
     */
    
    private String buildFilteredQueryString(ArrayList<Facet> facets, Facet relFacet, String facetParam, String facetValue){
              
        ArrayList<String> queryStrings = new ArrayList<String>();
        
        Iterator<Facet> fit = facets.iterator();
        
        while(fit.hasNext()){
            
            String queryString = "";
            
            Facet facet = fit.next();
            if(facet == relFacet){
                
                queryString = facet.getAsFilteredQueryString(facetParam, facetValue);
                
            }
            else{
                
                queryString = facet.getAsQueryString();
                
            }
            
            
            if(!"".equals(queryString)){
                
                queryStrings.add(queryString);
                
            }
            
        }
        
        String filteredQueryString = "";
        Iterator<String> qsit = queryStrings.iterator();
        while(qsit.hasNext()){
            
            filteredQueryString += qsit.next();
            if(qsit.hasNext()) filteredQueryString += "&";
            
        }
        return filteredQueryString;
        
    }
    
    private void passCompletedQueryToDateFacet(ArrayList<Facet> facets, SolrQuery completedQuery){
        
        Iterator<Facet> fit = facets.iterator();
        
        while(fit.hasNext()){
            
            Facet nowFacet = fit.next();
            
            if(nowFacet instanceof DateFacet){
             
                ((DateFacet) nowFacet).setCompletedQuery(completedQuery);
                
                
            }
            
        }
        
        
    }
        
    
    // <editor-fold defaultstate="collapsed" desc="HttpServlet methods. Click on the + sign on the left to edit the code.">
    /** 
     * Handles the HTTP <code>GET</code> method.
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    /** 
     * Handles the HTTP <code>POST</code> method.
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    /** 
     * Returns a short description of the servlet.
     * @return a String containing servlet description
     */
    @Override
    public String getServletInfo() {
        return "Short description";
    }// </editor-fold>
}
