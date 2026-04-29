package com.bjsrestaurants.dxp.core.servlets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import javax.servlet.Servlet;

import  com.day.cq.commons.jcr.JcrConstants;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.sling.servlets.annotations.SlingServletPaths;

import static com.bjsrestaurants.dxp.core.utils.Constants.*;


@Component(service = Servlet.class, property = { Constants.SERVICE_DESCRIPTION + "=Query Builder servlet",
        "sling.servlet.methods=" + HttpConstants.METHOD_GET })
@SlingServletPaths(PDPQueryReaderServlet.ENDPOINT)
public class PDPQueryReaderServlet extends SlingSafeMethodsServlet {

    /**
     * Generated serialVersionUID
     */
    private static final long serialVersionUID = 1L;

    /**
     * Logger
     */
    private static final Logger log = LoggerFactory.getLogger(PDPQueryReaderServlet.class);
    protected static final String ENDPOINT = "/bin/updatepdpcontentdata";

    private static final  String LOG_START = "PRODUCT PROPS REPLACEMENT - ";
    private static final  String ERROR_MESSAGE = "ERROR FOUND {} ";
    private static final  String NOT_FOUND = "NOT FOUND {} ";
    private static final  String ERROR_CF = "ERROR UPDATING CF";
    private static final  String ERROR_PRODUCT_PAGE = "ERROR UPDATING PRODUCT PAGES";

    private static final  String SUCCESS_UPDATE_PRODUCT_PAGE = "FINISHED UPDATING A TOTAL OF {} PRODUCTS PAGES";
    private static final  String SUCCESS_UPDATE_CF = "FINISHED UPDATING A TOTAL OF {} PRODUCTS CF";
    private static final  String NOT_SUCCESS_UPDATE_PRODUCT_PAGE = "A TOTAL OF {} ROWS WERE NOT UPDATED";



    private static final String FILE_NAME_PARAM = "fileName";
    private static final String UPDATE_PROPERTY = "updateProperty";
    private static final String UPDATE_CF = "updateCF";
    private static final String UPDATE_PAGE_BY_ID = "updatePageByID";
    private static final String DELIMITER = "delimiter";
    private static final String ID_COLUMN_NAME_PARAM = "identifierColumnName";
    private static final String UPDATE_COLUMN_NAME_PARAM = "updateColumnName";

    private static final Set<String> ALLOWED_DELIMITERS = new HashSet<>(Arrays.asList(",", ";"));
    private static final String DEFAULT_DELIMITER = ",";

    public Map<String, String> readCSV(SlingHttpServletRequest request, String delimiter)
            throws IOException {

        Resource resource;
        ResourceResolver resourceResolver = request.getResourceResolver();
        Map<String, String> result = new HashMap<>();
        BufferedReader br = null;

        String fileName = request.getParameter(FILE_NAME_PARAM);
        String idColumnName = request.getParameter(ID_COLUMN_NAME_PARAM);
        String updateColumnName = request.getParameter(UPDATE_COLUMN_NAME_PARAM);
        String idColumn;

        if (delimiter == null || !ALLOWED_DELIMITERS.contains(delimiter)) {
            log.warn("{} Invalid or missing delimiter {}", LOG_START, delimiter);
            delimiter = DEFAULT_DELIMITER;
        }
        final String safeDelimiterPattern = Pattern.quote(delimiter);

        if (fileName == null || updateColumnName == null) {
            log.debug("{} MISSING ONE OR MORE PARAMETERS", LOG_START);
            return result;
        }

        resource = resourceResolver.getResource(REPORTS_PATH + fileName);

        if (resource != null) {
            Asset asset = resource.adaptTo(Asset.class);
            if(asset != null) {
                Rendition rendition = asset.getOriginal();
                if (rendition != null) {
                    ValueMap props = rendition.getProperties();
                    if (props != null) {
                        br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(props.get(com.day.cq.commons.jcr.JcrConstants.JCR_DATA, InputStream.class))));
                        int idColumnIndex = -1;
                        int updateColumnIndex = 0;
                        String line = br.readLine();
                        String[] splitLine = line.split(safeDelimiterPattern);

                        for ( int i = 0; i < splitLine.length ; i++){
                            if ( splitLine[i].contains(updateColumnName) ) {
                                updateColumnIndex = i;
                            }

                            if ( splitLine[i].contains(idColumnName) ) {
                                idColumnIndex = i;
                            }
                        }

                        if (idColumnIndex == -1) {
                            log.debug("{} IS MISSING ID/TITLE COLUMN", fileName);
                            return result;
                        }

                        while ( (line = br.readLine()) != null) {
                            splitLine = line.split(safeDelimiterPattern);

                            if ( splitLine.length > idColumnIndex && splitLine.length > updateColumnIndex ) {
                                idColumn = splitLine[idColumnIndex].replaceAll("\"", "").replaceAll(" ", "");
                                result.put(idColumn, splitLine[updateColumnIndex]);
                            } else {
                                log.debug("{} CORRUPT LINE ->  {}", LOG_START, line);
                            }
                        }
                        br.close();
                    }
                }
            }

        } else {
            log.debug("{} FILE MISSING", LOG_START);
        }

        return result;
    }

    private void updateCFImage(ResourceResolver resourceResolver, Map<String, String> updatedValues,
                               String updateProperty ) {
        Session session = resourceResolver.adaptTo(Session.class);
        String jcrPathProductId;
        Resource pdpResource;
        Node pdpResourceData;
        Resource resource;
        for (Map.Entry<String, String> product : updatedValues.entrySet()) {

            jcrPathProductId = StringUtils.join(PRODUCTS_ROOT_PATH + FORWARDSLASH, product.getKey(), PRODUCTS_MASTER_PATH);
            pdpResource = resourceResolver.getResource(jcrPathProductId);

            if (pdpResource != null) {

                pdpResourceData = pdpResource.adaptTo(Node.class);

                try {
                    if ( updateProperty == IMAGE) {
                        resource = resourceResolver.getResource(product.getValue());
                        if (resource != null) {
                            log.debug("{} Updating {}", LOG_START, jcrPathProductId);
                            pdpResourceData.setProperty(updateProperty, product.getValue());
                        } else {
                            log.error("{} {} IMAGE MISSING", LOG_START, product.getValue());
                        }
                    } else {
                        pdpResourceData.setProperty(updateProperty, product.getValue());
                    }

                } catch (RepositoryException e) {
                    log.error(ERROR_MESSAGE + ERROR_CF, e.getMessage());
                }
            } else {
                log.error("{} {} CONTENT FRAGMENT MISSING", LOG_START, product.getKey());
            }
        }
        try {
            session.save();
        } catch (RepositoryException e) {
            log.error(ERROR_MESSAGE + ERROR_CF, e.getMessage());
        }
        log.debug(SUCCESS_UPDATE_CF, updatedValues.size());
        session.logout();
    }

    private void updatePages(ResourceResolver resourceResolver, Map<String, String> updatedValues,
                             boolean updatePageById, String updateProperty) throws RepositoryException {
        Session session = resourceResolver.adaptTo(Session.class);
        String sql2Query = "SELECT page.* FROM [cq:Page] AS page\n" +
                "INNER JOIN [cq:PageContent] AS jcrcontent ON ISCHILDNODE (jcrcontent, page)\n" +
                "WHERE ISDESCENDANTNODE(page ,\"/content/bjsdxp/us/en\")\n" +
                "AND jcrcontent .[sling:resourceType] = \"bjsdxp/components/page\"\n" +
                "ORDER BY jcrcontent .[jcr:title] ";
        Query query = session.getWorkspace().getQueryManager().createQuery(sql2Query, Query.JCR_SQL2);
        QueryResult queryResult = query.execute();
        NodeIterator pages = queryResult.getNodes();
        Map<String, String> auxProductMap = new HashMap<>(updatedValues);

        String propertyKey = (updatePageById) ? ID : JcrConstants.JCR_TITLE;

        while ( pages.hasNext() ) {

            Node nodePage = pages.nextNode();
            Node pageJcr = resourceResolver.getResource(nodePage.getPath() + JCR_CONTENT).adaptTo(Node.class);

            if (pageJcr.hasProperty(propertyKey)) {

                String page = pageJcr.getProperty(propertyKey).getValue().toString().replaceAll(" ", "");

                if ( auxProductMap.containsKey(page) ) {
                    log.debug("{} Updating {}", LOG_START, pageJcr.getPath());
                    pageJcr.setProperty(updateProperty, updatedValues.get(page));
                    auxProductMap.remove(page);
                }
            }
        }

        log.debug(SUCCESS_UPDATE_PRODUCT_PAGE , updatedValues.size() - auxProductMap.size());
        log.debug(NOT_SUCCESS_UPDATE_PRODUCT_PAGE, auxProductMap.size());

        for (Map.Entry<String, String> product : auxProductMap.entrySet()) {
            log.debug("{} {}", NOT_FOUND, product.getKey());
        }

        try {
            session.save();
        } catch (RepositoryException e) {
            log.error(ERROR_MESSAGE + ERROR_PRODUCT_PAGE, e.getMessage());
        }
        session.logout();
    }

    /**
     * Overridden doGet() method which executes on HTTP GET request
     */
    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) {
        try {
            String updateProperty = request.getParameter(UPDATE_PROPERTY);
            boolean updateCF = Boolean.parseBoolean(request.getParameter(UPDATE_CF));
            boolean updatePageById = Boolean.parseBoolean(request.getParameter(UPDATE_PAGE_BY_ID));
            String delimiter = request.getParameter(DELIMITER);
            Map<String, String> updatedValues = readCSV(request, delimiter);
            ResourceResolver resourceResolver = request.getResourceResolver();

            if (!updatedValues.isEmpty()) {
                if (updateCF) {
                    updateCFImage(resourceResolver, updatedValues, updateProperty);
                } else {
                    updatePages(resourceResolver, updatedValues, updatePageById, updateProperty);
                }
            }

        } catch (IOException | RepositoryException e) {
            log.error(ERROR_MESSAGE, e.getMessage());
        }
    }
}
