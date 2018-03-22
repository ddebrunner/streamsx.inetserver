//
// *******************************************************************************
// * Copyright (C)2016, International Business Machines Corporation and *
// * others. All Rights Reserved. *
// *******************************************************************************
//
package com.ibm.streamsx.inet.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

import org.apache.http.entity.ContentType;

import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.Attribute;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.TupleAttribute;
import com.ibm.streams.operator.Type.MetaType;
import com.ibm.streams.operator.logging.TraceLevel;
import com.ibm.streams.operator.meta.CollectionType;
import com.ibm.streams.operator.model.Parameter;

/**
 * Handles the API (parameters) for the HTTPRequest operator.
 * 
 */
class HTTPRequestOperAPI extends AbstractOperator {

    static final String DESC = "Issue an HTTP request of the specified method for each input tuple. For method `NONE`, the request is supressed."
            + "The URL and  method of the HTTP request either come from the input tuple using attributes "
            + "specified by the `url` and `method` parameters, or can be fixed using the `fixedUrl` "
            + "and `fixedMethod` parameters. These parameters can be mixed, for example the URL "
            + "can be fixed with `fixedUrl` while the method is set from each tuple using `method`. "
            + "A content type is required for `POST` and `PUT` method. The content type is specified by `contenType`or "
            + "`fixedContentType` parameter.\\n\\n"
            + "The contents of the request is dependent on the method type.\\n"
            + "# GET\\n"
            + "An HTTP GET request is made, any request attributes are converted to URL query parameters.\\n"
            + "# POST\\n"
            + "An HTTP PUT request is made, any request attributes are set as the body of the request message if parameter `requestBody` is not present. "
            + "If parameter `requestBody` is present, the body of the request is generated from this attribute.\\n"
            + "# PUT\\n"
            + "An HTTP PUT request is made, any request attributes are set as the body of the request message if parameter `requestBody` is not present."
            + "If parameter `requestBody` is present, the body of the request is generated from this attribute.\\n"
            + "# OPTIONS\\n"
            + "No message body is generated.\\n"
            + "# HEAD\\n"
            + "An HTTP HEAD request is made, any request attributes are converted to URL query parameters.\\n"
            + "# DELETE\\n"
            + "No message body is generated.\\n"
            + "# TRACE\\n"
            + "No message body is generated.\\n"
            + "# NONE\\n"
            + "No http request is generated but an output tuple is genrated if the output port is present."
            + "# Request Attributes\\n" + "Attributes from the input tuple are request parameters except for:\\n"
            + "* Any attribute specified by parameters `url`, `method` and `contentType`.\\n";

    //register trace and log facility
    protected static Logger logger = Logger.getLogger("com.ibm.streams.operator.log." + HTTPRequestOperAPI.class.getName());
    protected static Logger tracer = Logger.getLogger(HTTPRequestOperAPI.class.getName());
    
    //request parameters
    private String fixedUrl = null;
    private TupleAttribute<Tuple, String> url;
    private HTTPMethod fixedMethod = null;
    private TupleAttribute<Tuple, String> method;
    private String fixedContentType = null;
    private TupleAttribute<Tuple, String> contentType;
    protected ContentType contentTypeToUse = null;
    private List<String> extraHeaders = Collections.emptyList();
    private TupleAttribute<Tuple, String> requestBody = null;  //request body
    protected Set<String> requestAttributes = new HashSet<>(); //Attributes that are part of the request.
    
    //output parameters
    private String outputDataLine = null;
    private String outputBody = null;
    private String outputStatus = null;
    private String outputStatusCode = null;
    private String outputHeader = null;
    private String outputContentEncoding = null;
    private String outputContentType = null;
    
    //internal operator state
    protected boolean shutdown = false;
    protected boolean hasDataPort = false;
    boolean hasOutputAttributeParameter = false;

    // Function to return theurl, method, content type from an input tuple or fixed
    private Function<Tuple, HTTPMethod> methodGetter;
    private Function<Tuple, String> urlGetter;
    private Function<Tuple, ContentType> contentTypeGetter;
    

    /********************************
     * request parameters
     ********************************/
    @Parameter(optional = true, description = "Attribute that specifies the URL to be used in the"
            + "HTTP request for a tuple. One and only one of `url` and `fixedUrl` must be specified.")
    public void setUrl(TupleAttribute<Tuple, String> url) {
        this.url = url;
    }
    @Parameter(optional = true, description = "Fixed URL to send HTTP requests to. Any tuple received"
            + " on the input port results in a results to the URL provided."
            + " One and only one of `url` and `fixedUrl` must be specified.")
    public void setFixedUrl(String fixedUrl) {
        this.fixedUrl = fixedUrl;
    }
    @Parameter(optional = true, description = "Attribute that specifies the method to be used in the"
            + "HTTP request for a tuple. One and only one of `method` and `fixedMethod` must be specified.")
    public void setMethod(TupleAttribute<Tuple, String> method) {
        this.method = method;
    }
    @Parameter(optional = true, description = "Fixed method for each HTTP request. Every HTTP request "
            + " uses the method provided. One and only one of `method` and `fixedMethod` must be specified.")
    public void setFixedMethod(HTTPMethod fixedMethod) {
        this.fixedMethod = fixedMethod;
    }
    @Parameter(optional = true, description = "Fixed MIME content type of entity for `POST` and `PUT` requests. "
            + "Only one of `contentType` and `fixedContentType` must be specified."
            + " Defaults to `application/json`.")
    public void setFixedContentType(String fixedContentType) {
        this.fixedContentType = fixedContentType;
        /*this.fixedContentType = ContentType.getByMimeType(fixedContentType);
        if (fixedContentType == null) {
            throw new IllegalArgumentException("Argument of contentType:"+contentType+" is invalid!");
        }*/
        /*if (contentType.equals(ContentType.APPLICATION_JSON.getMimeType())) {
            this.contentType = ContentType.APPLICATION_JSON;
        } else if (contentType.equals(ContentType.APPLICATION_FORM_URLENCODED.getMimeType())) {
            this.contentType = ContentType.APPLICATION_FORM_URLENCODED;
        } else {
            String allowedIs = ContentType.APPLICATION_JSON.getMimeType()+
                           "|"+ContentType.APPLICATION_FORM_URLENCODED.getMimeType();
            throw new IllegalArgumentException("Argument of contentType:"+contentType+" is invalid! Allowed is:"+allowedIs);
        }*/
    }
    @Parameter(optional = true, description = "MIME content type of entity for `POST` and `PUT` requests. "
            + "Only one of `contentType` and `fixedContentType` must be specified."
            + " Defaults to `application/json`.")
    public void setContentType(TupleAttribute<Tuple, String> contentType) {
        this.contentType = contentType;
    }
    @Parameter(optional = true, description = "Extra headers to send with request, format is `Header-Name: value`.")
    public void setExtraHeaders(String[] extraHeaders) {
        //This is never called get values in initialize method
        //this.extraHeaders = operatorContext.getParameterValues("extraHeaders");
        //this.extraHeaders = extraHeaders;
    }
    @Parameter(optional=true, description="Names of the attributes which are part of the request body. The content of "
            + "these attributes are sent for any method that accepts an entity (PUT / POST). If this parameter is missing, "
            + "all attributes, excluding those that are used to specify the URL, method or content type, are used in the request body. "
            + "One empty element defines an empty list.")
    public void setRequestAttributes(String[] requestAttributes) {
        //This is never called
    }
    @Parameter(optional=true, description="Request body attribute. If this parameter is set, the body of PUT and POST requests "
            + " is taken from this attribute. This parameter is not allowed if parameter `requestAttributes` is set.")
    public void setRequestBody(TupleAttribute<Tuple, String> requestBody) {
        System.out.println("set attribute param");
        this.requestBody = requestBody;
    }
    
    /********************************
     * output parameters
     ********************************/
    @Parameter(optional=true, description="Name of the attribute to populate one line of the response data with. "
        + "If this parameter is set, the operators returns one tuple for each line in the resonse body but at least one tuple if the body is empty. "
        + "Only one of `outputDataLine` and `outputBody` must be specified."
        + "This parameter is not allowed if the operator has no output port. ")
        //+ "If the number of attributes of the output stream is greater than one, at least one of "
        //+ "`dataAttributeName`, `bodyAttributeName`, `statusAttributeName`, `statusCodeAttributeName`, `headerAttributeName, contentEncodingAttributeName or contentTypeAttributeName must be set.")
    public void setOutputDataLine(String outputDataLine) {
        this.outputDataLine = outputDataLine;
    }
    @Parameter(optional=true, description="Name of the attribute to populate the response body with. "
        + "If this parameter is set, the operators returns one tuple for each request. "
        + "Only one of `outputDataLine` and `outputBody` must be specified."
        + "This parameter is not allowed if the operator has no output port. ")
        //+ "This parameter is mandatory if the number of attributes of the output stream is greater than one.")
    public void setOutputBody(String outputBody) {
        this.outputBody = outputBody;
    }
    @Parameter(optional=true, description="Name of the attribute to populate the response status line with. "
        + "This parameter is not allowed if the operator has no output port. ")
        //+ "This parameter is mandatory if the number of attributes of the output stream is greater than one.")
    public void setOutputStatus(String outputStatus) {
        this.outputStatus = outputStatus;
    }
    @Parameter(optional=true, description="Name of the attribute to populate the response status code as integer with. "
        + "The type of this attribute must be int32. "
        + "This parameter is not allowed if the operator has no output port. ")
        //+ "This parameter is mandatory if the number of attributes of the output stream is greater than one.")
    public void setOutputStatusCode(String outputStatusCode) {
        this.outputStatusCode = outputStatusCode;
    }
    @Parameter(optional=true, description="Name of the attribute to populate the response header information with. "
        + "The type of this attribute must be string list. "
        + "This parameter is not allowed if the operator has no output port. ")
        //+ "This parameter is mandatory if the number of attributes of the output stream is greater than one.")
    public void setOutputHeader(String outputHeader) {
        this.outputHeader = outputHeader;
    }
    @Parameter(optional=true, description="Name of the attribute to populate the response data with. "
        + "This parameter is not allowed if the operator has no output port. ")
        //+ "This parameter is mandatory if the number of attributes of the output stream is greater than one.")
    public void setOutputContentEncoding(String outputContentEncoding) {
        this.outputContentEncoding = outputContentEncoding;
    }
    @Parameter(optional=true, description="Name of the attribute to populate the response data with. "
        + "This parameter is not allowed if the operator has no output port. ")
        //+ "This parameter is mandatory if the number of attributes of the output stream is greater than one.")
    public void setOutputContentType(String outputContentType) {
        this.outputContentType = outputContentType;
    }

    /*****************************************
    * Initialize
    *****************************************/
    public void initialize(com.ibm.streams.operator.OperatorContext context) throws Exception {
        tracer.log(TraceLevel.TRACE, "initialize(context)");
        super.initialize(context);

        Set<String> parameterNames = context.getParameterNames();

        //Url
        if (fixedUrl != null) {
            urlGetter = t -> fixedUrl;
        } else {
            urlGetter = tuple -> url.getValue(tuple);
        }
        //Method
        if (fixedMethod != null) {
            methodGetter = t -> fixedMethod;
        } else {
            methodGetter = tuple -> HTTPMethod.valueOf(method.getValue(tuple));
        }
        //content type
        if ((fixedContentType == null) && (contentType == null))
            fixedContentType = ContentType.APPLICATION_JSON.getMimeType();
        if (fixedContentType != null) {
            contentTypeToUse = ContentType.getByMimeType(fixedContentType);
            if (contentTypeToUse == null) {
                throw new IllegalArgumentException("Argument of contentType:"+fixedContentType+" is invalid!");
            }
            contentTypeGetter = t -> contentTypeToUse;
        } else {
            contentTypeGetter = tuple -> ContentType.getByMimeType(contentType.getValue(tuple));
        }
        //get values of list type here
        if (parameterNames.contains("extraHeaders")) {
            extraHeaders = context.getParameterValues("extraHeaders");
        }
        //Check whether all required request attributes are in input stream
        StreamSchema ss = getInput(0).getStreamSchema();
        Set<String> inputAttributeNames = ss.getAttributeNames();
        if (parameterNames.contains("requestAttributes")) {
            //read all attribute names from parameter
            List<String> reqAttrNames = new ArrayList<String>();
            reqAttrNames.addAll(context.getParameterValues("requestAttributes"));
            if ((reqAttrNames.size() == 1) && reqAttrNames.get(0).isEmpty()) //remove if there is only one empty element
                reqAttrNames.remove(0);
            if (inputAttributeNames.containsAll(reqAttrNames)) {
                requestAttributes.addAll(reqAttrNames);
            } else {
                throw new IllegalArgumentException("Input stream does not have all requestAttributes: "+reqAttrNames.toString());
            }
        } else {
            //no parameter 'requestAttributes' -> collect remaining attributes
            // Assume request attributes (sent for any method that accepts an entity)
            // are all attributes, excluding those that are used to specify the URL, method or content type.
            requestAttributes.addAll(inputAttributeNames);
            if (url != null)
                requestAttributes.remove(url.getAttribute().getName());
            if (method != null)
                requestAttributes.remove(method.getAttribute().getName());
            if (contentType != null)
                requestAttributes.remove(contentType.getAttribute().getName());
        }
        
        //output params ...
        if (parameterNames.contains("outputData")
         || parameterNames.contains("outputBody")
         || parameterNames.contains("outputStatus")
         || parameterNames.contains("outputStatusCode")
         || parameterNames.contains("outputHeader")
         || parameterNames.contains("outputContentEncoding")
         || parameterNames.contains("outputContentType")
         ) {
            hasOutputAttributeParameter = true;
        }
        if (context.getNumberOfStreamingOutputs() == 0) {
            if (hasOutputAttributeParameter)
                throw new Exception("Operator has output attribute name parameter but has no output port");
        } else {
            hasDataPort = true;
            
            Set<String> outPortAttributes = getOutput(0).getStreamSchema().getAttributeNames();
            String missingOutAttribute = null;
            if (outputDataLine != null) {
                if (outPortAttributes.contains(outputDataLine)) {
                    MetaType paramType = getOutput(0).getStreamSchema().getAttribute(outputDataLine).getType().getMetaType();
                    if(paramType!=MetaType.USTRING && paramType!=MetaType.RSTRING)
                        throw new IllegalArgumentException("Only types \""+MetaType.USTRING+"\" and \""+MetaType.RSTRING+"\" allowed for param \""+outputDataLine+"\"");
                } else {
                    missingOutAttribute = outputDataLine;
                }
            }
            if (outputBody != null) {
                if (outPortAttributes.contains(outputBody)) {
                    MetaType paramType = getOutput(0).getStreamSchema().getAttribute(outputBody).getType().getMetaType();
                    if(paramType!=MetaType.USTRING && paramType!=MetaType.RSTRING)
                        throw new IllegalArgumentException("Only types \""+MetaType.USTRING+"\" and \""+MetaType.RSTRING+"\" allowed for param \""+outputBody+"\"");
                } else {
                    missingOutAttribute = outputBody;
                }
            }
            if (outputStatus != null) {
                if (outPortAttributes.contains(outputStatus)) {
                    MetaType paramType = getOutput(0).getStreamSchema().getAttribute(outputStatus).getType().getMetaType();
                    if(paramType!=MetaType.USTRING && paramType!=MetaType.RSTRING)
                        throw new IllegalArgumentException("Only types \""+MetaType.USTRING+"\" and \""+MetaType.RSTRING+"\" allowed for param \""+outputStatus+"\"");
                } else {
                    missingOutAttribute = outputStatus;
                }
            }
            if (outputStatusCode != null) {
                if (outPortAttributes.contains(outputStatusCode)) {
                    MetaType paramType = getOutput(0).getStreamSchema().getAttribute(outputStatusCode).getType().getMetaType();
                    if(paramType!=MetaType.INT32)
                        throw new IllegalArgumentException("Only types \""+MetaType.INT32+"\" allowed for param \""+outputStatusCode+"\"");
                } else {
                    missingOutAttribute = outputStatusCode;
                }
            }
            if (outputHeader != null) {
                if (outPortAttributes.contains(outputHeader)) {
                    MetaType paramType = getOutput(0).getStreamSchema().getAttribute(outputHeader).getType().getMetaType();
                    if(paramType==MetaType.LIST) {
                        Attribute attr = getOutput(0).getStreamSchema().getAttribute(outputHeader);
                        CollectionType collType = (CollectionType) attr.getType();
                        com.ibm.streams.operator.Type elemType = collType.getElementType();
                        MetaType lelemTypeM = elemType.getMetaType();
                        if (lelemTypeM != MetaType.RSTRING)
                            throw new IllegalArgumentException("Only element types \""+MetaType.RSTRING+"\" allowed for param \""+outputHeader+"\"");
                    } else {
                        throw new IllegalArgumentException("Only types \""+MetaType.LIST+"\" allowed for param \""+outputHeader+"\"");
                    }
                } else {
                    missingOutAttribute = outputHeader;
                }
            }
            if (outputContentEncoding != null) {
                if (outPortAttributes.contains(outputContentEncoding)) {
                    MetaType paramType = getOutput(0).getStreamSchema().getAttribute(outputContentEncoding).getType().getMetaType();
                    if(paramType!=MetaType.USTRING && paramType!=MetaType.RSTRING)
                        throw new IllegalArgumentException("Only types \""+MetaType.USTRING+"\" and \""+MetaType.RSTRING+"\" allowed for param \""+outputContentEncoding+"\"");
                } else {
                    missingOutAttribute = outputContentEncoding;
                }
            }
            if (outputContentType != null) {
                if (outPortAttributes.contains(outputContentType)) {
                    MetaType paramType = getOutput(0).getStreamSchema().getAttribute(outputContentType).getType().getMetaType();
                    if(paramType!=MetaType.USTRING && paramType!=MetaType.RSTRING)
                        throw new IllegalArgumentException("Only types \""+MetaType.USTRING+"\" and \""+MetaType.RSTRING+"\" allowed for param \""+outputContentType+"\"");
                } else {
                    missingOutAttribute = outputContentType;
                }
            }
            if (missingOutAttribute != null) 
                throw new IllegalArgumentException("No attribute with name "+missingOutAttribute+" found in schema of output port 0.");
        }

        //Check whether all attributes are string type for urlencoded doc
        /*if (contentType.getMimeType() == ContentType.APPLICATION_FORM_URLENCODED.getMimeType()) {
            Iterator<Attribute> ia = ss.iterator();
            while (ia.hasNext()) {
                Attribute attr =ia.next();
                String name = attr.getName();
                if (requestAttributes.contains(name)) {
                    MetaType paramType = attr.getType().getMetaType();
                    if(paramType!=MetaType.USTRING && paramType!=MetaType.RSTRING) {
                        throw new IllegalArgumentException("Attribute="+name+": If content type is:"+ContentType.APPLICATION_FORM_URLENCODED.getMimeType()+", request attributes must have type \""+MetaType.USTRING+"\" or \""+MetaType.RSTRING+"\"");
                    }
                }
            }
        }*/
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    /*
     * Methods uses by implementation class
     */
    HTTPMethod getMethod(Tuple tuple) { return methodGetter.apply(tuple); }
    String getUrl(Tuple tuple) { return urlGetter.apply(tuple); }
    ContentType getContentType(Tuple tuple) { return contentTypeGetter.apply(tuple); }

    Set<String> getRequestAttributes() { return requestAttributes; }

    boolean isRequestAttribute(Object name) {
        return getRequestAttributes().contains(name);
    }

    public List<String> getExtraHeaders()    { return extraHeaders; }
    public TupleAttribute<Tuple, String> getRequestBody() { return requestBody; }
    
    public String getOutputDataLine()        { return outputDataLine; }
    public String getOutputBody()            { return outputBody; }
    public String getOutputStatus()          { return outputStatus; }
    public String getOutputStatusCode()      { return outputStatusCode; }
    public String getOutputHeader()          { return outputHeader; }
    public String getOutputContentEncoding() { return outputContentEncoding; }
    public String getOutputContentType()     { return outputContentType; }
}