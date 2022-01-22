package gov.nasa.pds.harvest.dao;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;

import gov.nasa.pds.registry.common.es.client.EsUtils;
import gov.nasa.pds.registry.common.es.client.HttpConnectionFactory;
import gov.nasa.pds.registry.common.es.dao.DaoUtils;
import gov.nasa.pds.registry.common.util.CloseUtils;


/**
 * Loads data from an NJSON (new-line-delimited JSON) file into Elasticsearch.
 * NJSON file has 2 lines per record: 1 - primary key, 2 - data record.
 * This is the standard file format used by Elasticsearch bulk load API.
 * Data are loaded in batches.
 * 
 * @author karpenko
 */
public class DataLoader
{
    private int printProgressSize = 500;
    private int batchSize = 100;
    private int totalRecords;
    
    private Logger log;
    private HttpConnectionFactory conFactory; 


    /**
     * Constructor
     * @param esUrl Elasticsearch URL
     * @param esIndex Elasticsearch index name
     * @param esAuthFile Elasticsearch authentication configuration file
     * @throws Exception an exception
     */
    public DataLoader(String esUrl, String esIndex, String esAuthFile) throws Exception
    {
        log = LogManager.getLogger(this.getClass());
        conFactory = new HttpConnectionFactory(esUrl, esIndex, "_bulk?refresh=wait_for");
        conFactory.initAuth(esAuthFile);
    }
    
    
    /**
     * Load data into Elasticsearch
     * @param data NJSON data. (2 lines per record)
     * @throws Exception an exception
     */
    public int loadBatch(List<String> data) throws Exception
    {
        if(data == null || data.isEmpty()) return 0;
        if(data.size() % 2 != 0) throw new Exception("Data list size should be an even number.");
        
        HttpURLConnection con = null;
        
        try
        {
            con = conFactory.createConnection();
            con.setDoInput(true);
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setRequestProperty("content-type", "application/x-ndjson; charset=utf-8");
            
            OutputStreamWriter writer = new OutputStreamWriter(con.getOutputStream(), "UTF-8");
            
            for(int i = 0; i < data.size(); i+=2)
            {
                writer.write(data.get(i));
                writer.write("\n");
                writer.write(data.get(i+1));
                writer.write("\n");
            }
            
            writer.flush();
            writer.close();
        
            // Check for Elasticsearch errors.
            String respJson = DaoUtils.getLastLine(con.getInputStream());
            log.debug(respJson);
            
            int numErrors = processErrors(respJson);
            // Return number of successfully saved records
            // NOTE: data list has two lines per record (primary key + data)
            return data.size() / 2 - numErrors;
        }
        catch(UnknownHostException ex)
        {
            throw new Exception("Unknown host " + conFactory.getHostName());
        }
        catch(IOException ex)
        {
            // Get HTTP response code
            int respCode = getResponseCode(con);
            if(respCode <= 0) throw ex;
            
            // Try extracting JSON from multi-line error response (last line) 
            String json = DaoUtils.getLastLine(con.getErrorStream());
            if(json == null) throw ex;
            
            // Parse error JSON to extract reason.
            String msg = EsUtils.extractReasonFromJson(json);
            if(msg == null) msg = json;
            
            throw new Exception(msg);
        }
    }
    
    
    /**
     * Set data batch size
     * @param size batch size
     */
    public void setBatchSize(int size)
    {
        if(size <= 0) throw new IllegalArgumentException("Batch size should be > 0");
        this.batchSize = size;
    }

    
    /**
     * Load data from an NJSON (new-line-delimited JSON) file into Elasticsearch.
     * @param file NJSON (new-line-delimited JSON) file to load
     * @throws Exception an exception
     */
    public void loadFile(File file) throws Exception
    {
        log.info("Loading ES data file: " + file.getAbsolutePath());
        
        BufferedReader rd = new BufferedReader(new FileReader(file));
        loadData(rd);
    }

    
    /**
     * Load NJSON data from a reader.
     * @param rd reader
     * @throws Exception an exception
     */
    private void loadData(BufferedReader rd) throws Exception
    {
        totalRecords = 0;
        
        try
        {
            String firstLine = rd.readLine();
            // File is empty
            if(firstLine == null || firstLine.isEmpty()) return;
            
            while((firstLine = loadBatch(rd, firstLine)) != null)
            {
                if(totalRecords != 0 && (totalRecords % printProgressSize) == 0)
                {
                    log.info("Loaded " + totalRecords + " document(s)");
                }
            }
            
            log.info("Loaded " + totalRecords + " document(s)");
        }
        finally
        {
            CloseUtils.close(rd);
        }
    }

    
    /**
     * Load next batch of NJSON (new-line-delimited JSON) data.
     * @param fileReader Reader object with NJSON data.
     * @param firstLine NJSON file has 2 lines per record: 1 - primary key, 2 - data record.
     * This is the primary key line.
     * @return First line of 2-line NJSON record (line 1: primary key, line 2: data)
     * @throws Exception an exception
     */
    private String loadBatch(BufferedReader fileReader, String firstLine) throws Exception
    {
        HttpURLConnection con = null;
        
        try
        {
            con = conFactory.createConnection();
            con.setDoInput(true);
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setRequestProperty("content-type", "application/x-ndjson; charset=utf-8");
            
            OutputStreamWriter writer = new OutputStreamWriter(con.getOutputStream(), "UTF-8");
            
            // First record
            String line1 = firstLine;
            String line2 = fileReader.readLine();
            if(line2 == null) throw new Exception("Premature end of file");
            
            writer.write(line1);
            writer.write("\n");
            writer.write(line2);
            writer.write("\n");
            
            int numRecords = 1;
            while(numRecords < batchSize)
            {
                line1 = fileReader.readLine();
                if(line1 == null) break;
                
                line2 = fileReader.readLine();
                if(line2 == null) throw new Exception("Premature end of file");
                
                writer.write(line1);
                writer.write("\n");
                writer.write(line2);
                writer.write("\n");
                
                numRecords++;
            }
            
            if(numRecords == batchSize)
            {
                // Let's find out if there are more records
                line1 = fileReader.readLine();
                if(line1 != null && line1.isEmpty()) line1 = null;
            }
            
            writer.flush();
            writer.close();
        
            // Check for Elasticsearch errors.
            String respJson = DaoUtils.getLastLine(con.getInputStream());
            log.debug(respJson);
            
            int numErrors = processErrors(respJson);
            totalRecords += (numRecords - numErrors);

            return line1;
        }
        catch(UnknownHostException ex)
        {
            throw new Exception("Unknown host " + conFactory.getHostName());
        }
        catch(IOException ex)
        {
            // Get HTTP response code
            int respCode = getResponseCode(con);
            if(respCode <= 0) throw ex;
            
            // Try extracting JSON from multi-line error response (last line) 
            String json = DaoUtils.getLastLine(con.getErrorStream());
            if(json == null) throw ex;
            
            // Parse error JSON to extract reason.
            String msg = EsUtils.extractReasonFromJson(json);
            if(msg == null) msg = json;
            
            throw new Exception(msg);
        }
    }

    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private int processErrors(String resp)
    {
        int numErrors = 0;

        try
        {
            // TODO: Use streaming parser. Stop parsing if there are no errors.
            // Parse JSON response
            Gson gson = new Gson();
            Map json = (Map)gson.fromJson(resp, Object.class);
            
            Boolean hasErrors = (Boolean)json.get("errors");
            if(hasErrors)
            {                
                List<Object> list = (List)json.get("items");
             
                // List size = batch size (one item per document)
                for(Object item: list)
                {
                    Map action = (Map)((Map)item).get("index");
                    if(action == null)
                    {
                        action = (Map)((Map)item).get("create");
                        if(action != null)
                        {
                            String status = String.valueOf(action.get("status"));
                            // For "create" requests status=409 means that the record already exists.
                            // It is not an error. We use "create" action to insert records which don't exist
                            // and keep existing records as is. We do this when loading an old LDD and more
                            // recent version of the LDD is already loaded.
                            // NOTE: Gson JSON parser stores numbers as floats. 
                            // The string value is usually "409.0". Can it be something else?
                            if(status.startsWith("409")) 
                            {
                                // Increment to properly report number of processed records.
                                numErrors++;
                                continue;
                            }
                        }
                    }
                    if(action == null) continue;
                    
                    String id = (String)action.get("_id");
                    Map error = (Map)action.get("error");
                    if(error != null)
                    {
                        String message = (String)error.get("reason");
                        log.error("LIDVID = " + id + ", Message = " + message);
                        numErrors++;
                    }
                }
            }

            return numErrors;
        }
        catch(Exception ex)
        {
            return 0;
        }
    }
    
    
    /**
     * Get HTTP response code, e.g., 200 (OK)
     * @param con HTTP connection
     * @return HTTP response code, e.g., 200 (OK)
     */
    private static int getResponseCode(HttpURLConnection con)
    {
        if(con == null) return -1;
        
        try
        {
            return con.getResponseCode();
        }
        catch(Exception ex)
        {
            return -1;
        }
    }

    
}
