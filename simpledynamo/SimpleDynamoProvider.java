package edu.buffalo.cse.cse486586.simpledynamo;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Formatter;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.util.Log;

import static java.lang.System.exit;

public class SimpleDynamoProvider extends ContentProvider {
    static final String TAG = SimpleDynamoProvider.class.getSimpleName();
    static final String[] REMOTE_PORT={"11108","11112","11116","11120","11124"};
    static final int SERVER_PORT=10000;
    volatile static int p;//used in *
    String portStr;//this is the currentport which is the node id
    String myPort;//this is the current remote port number
    static TreeMap<String,String> HashtoPort = new TreeMap<String,String>();//Hashed NodeId,NodeId
    String keyHash;
    String selectionHash;
    volatile boolean querywait;
    static String querybuff;
    volatile String starstring;
    Boolean localflag = false;//used in query
    Uri mUri = buildUri("content", "edu.buffalo.cse.cse486586.simpledynamo.provider");
    Object lock1 = new Object();
    Object lock2= new Object();


    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // TODO Auto-generated method stub
        if (selection.equals("\"*\"")){//delete entire DHT
            for (String s : getContext().fileList())
                getContext().deleteFile(s);

        }
        //http://developer.android.com/reference/android/content/Context.html#deleteFile(java.lang.String)
        else if(selection.equals("\"@\"")){//delete everything from that avd
            for(String s: getContext().fileList())
                getContext().deleteFile(s);
        }
        else //to delete a particular record
            getContext().deleteFile(selection);
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // TODO Auto-generated method stub
        synchronized (lock1) {
            String filename = values.getAsString("key");
            String string = values.getAsString("value");

            try {
                keyHash = genHash(filename);
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }

            //2 cases to insert: When the hash is lesser than atleast 1 node and when its grater than all nodes

            String CoordHash;

            CoordHash = findCoord(keyHash);
            Log.v("In insert,just found the coordinator for " + filename + ",it is:", HashtoPort.get(CoordHash));


            //finding where to insert the replicas
            String Replica1Hash = "Replica 1 is nothing yet";
            String Replica2Hash = "Replica 2 is nothing yet";

            if (HashtoPort.get(CoordHash).equals("5558")) {
                try {
                    Log.v("Replica Corner case 1,CoordHash is 5558", HashtoPort.get(CoordHash));
                    Replica1Hash = HashtoPort.higherKey(CoordHash);
                    Log.v("Replica 1 at port", HashtoPort.get(Replica1Hash));
                    Replica2Hash = HashtoPort.firstKey();
                    Log.v("Replica 2 at port", HashtoPort.get(Replica2Hash));

                } catch (Exception e) {
                    Log.e("Replica node hashes not found ", "Maybe your CoordHash is wrong!");
                }
            } else if (HashtoPort.get(CoordHash).equals("5560")) {
                try {//handle corner cases
                    Log.v("Replica Corner case 2,CoordHash is 5560", HashtoPort.get(CoordHash));
                    Replica1Hash = HashtoPort.firstKey();
                    Log.v("Replica 1 at port", HashtoPort.get(Replica1Hash));
                    Replica2Hash = HashtoPort.higherKey(Replica1Hash);
                    Log.v("Replica 2 at port", HashtoPort.get(Replica2Hash));

                } catch (Exception e) {
                    Log.e("Replica node hashes not found ", "Maybe your CoordHash is wrong!");
                }
            } else {
                try {
                    Log.v("Replica Non Corner case 1,CoordHash is", HashtoPort.get(CoordHash));
                    Replica1Hash = HashtoPort.higherKey(CoordHash);

                    Log.v("Replica 1 at port", HashtoPort.get(Replica1Hash));
                    Replica2Hash = HashtoPort.higherKey(Replica1Hash);
                    Log.v("Replica 2 at port", HashtoPort.get(Replica2Hash));

                } catch (Exception e) {
                    Log.e("Replica node hashes not found ", "Maybe your CoordHash is wrong!");
                }
            }

            //found the Coordinator and Replica ports,now need to send them


            if (!portStr.equals(HashtoPort.get(CoordHash))) {
                Log.v("Sending client task for insert", "");
                String msg = "insert" + "_" + HashtoPort.get(CoordHash) + "_" + filename + "_" + string;
                new ClientTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, msg);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } else if (portStr.equals(HashtoPort.get(CoordHash))) {

                //now actually inserting to coordinator
                try {
                    Log.v("Inserting key: " + filename + " to coordinator:", HashtoPort.get(CoordHash));
                    FileOutputStream outputStream;
                    outputStream = getContext().openFileOutput(filename, Context.MODE_PRIVATE);
                    outputStream.flush();
                    outputStream.write(string.getBytes());
                    outputStream.close();
                    Log.v("Inserting success: ", filename + " , " + string);
                    //kv.put(filename,string);

                } catch (Exception e) {
                    Log.e("Error", "Key-Value Creation Failed");
                }
            }

            //checking if replica1 is to be inserted locally
            if (!portStr.equals(HashtoPort.get(Replica1Hash))) {
                Log.v("Sending client task for insert for replica 1", "");
                String msg = "replica" + "_" + HashtoPort.get(Replica1Hash) + "_" + filename + "_" + string;
                //new ClientTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,msg);
                fake_clienttask(msg);

            } else if (portStr.equals(HashtoPort.get(Replica1Hash))) {

                //now actually inserting to coordinator
                try {
                    Log.v("Replica 1:Inserting key: " + filename + " to coordinator:", HashtoPort.get(Replica1Hash));
                    FileOutputStream outputStream;
                    outputStream = getContext().openFileOutput(filename, Context.MODE_PRIVATE);
                    outputStream.flush();
                    outputStream.write(string.getBytes());
                    outputStream.close();
                    Log.v("Inserting success: ", filename + " , " + string);

                } catch (Exception e) {
                    Log.e("Error", "Key-Value Creation Failed");
                }
            }


            //checking if replica2 is to be inserted locally
            if (!portStr.equals(HashtoPort.get(Replica2Hash))) {
                Log.v("Sending client task for insert for replica 2", "");
                String msg = "replica" + "_" + HashtoPort.get(Replica2Hash) + "_" + filename + "_" + string;
                //new ClientTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,msg);
                fake_clienttask(msg);


            } else if (portStr.equals(HashtoPort.get(Replica2Hash))) {

                //now actually inserting to coordinator
                try {
                    Log.v("Replica 2:Inserting key: " + filename + " to coordinator:", HashtoPort.get(Replica2Hash));
                    FileOutputStream outputStream;
                    outputStream = getContext().openFileOutput(filename, Context.MODE_PRIVATE);
                    outputStream.flush();
                    outputStream.write(string.getBytes());
                    outputStream.close();
                    Log.v("Inserting success: ", filename + " , " + string);

                } catch (Exception e) {
                    Log.e("Error", "Key-Value Creation Failed");
                }
            }


            return uri;
        }
    }

    @Override
    public boolean onCreate() {
        // TODO Auto-generated method stub

        TelephonyManager tel = (TelephonyManager) this.getContext().getSystemService(Context.TELEPHONY_SERVICE);
        portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        myPort = String.valueOf((Integer.parseInt(portStr) * 2));
        Log.v("The telephony has detected AVD:",portStr);




        //inserting treemap and its hashvalues(Do i really have to use a treemap?)
        for(String  s:REMOTE_PORT) {
            try {
                s = String.valueOf(Integer.parseInt(s)/2); //converting 11108 to 5554 and so on
                HashtoPort.put(genHash(s), s);
                Log.v("Inserting Treemap <k,v>: ", genHash(s) + "," + s);
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        }

        //creating serversocket
        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            Log.e(TAG, "Can't create a ServerSocket");

        }
        int ct=0;
        String[] flist=getContext().fileList();

        for(String f:flist){
            Log.v("files are: ",f);
            ++ct;

        }
        if(ct==0){
            Log.v("There are no files stored","");

        }
        else{
            Log.v("There are files stored ct is",""+ct);
        }

        if(ct!=0) {
            String msg = "recovery" + "_" + portStr;
            new ClientTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, msg);
        }







        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // TODO Auto-generated method stub


        try {
            selectionHash = genHash(selection);
        }catch(NoSuchAlgorithmException e) {
            e.printStackTrace();
        }





        String[] colnames ={"key","value"};
        MatrixCursor mcursor = new MatrixCursor(colnames);

        //2 cases to query: When the hash is lesser than atleast 1 node and when its grater than all nodes

        String CoordHash="Nothing yet";
        CoordHash=findCoord(selectionHash);
        Log.v("In query,just found the coordinator,it is:",HashtoPort.get(CoordHash));


        if(selection.equals("\"@\"")){
            Log.v("@ query requested","");
            mcursor=query_all();
            return mcursor;

        }
        else if(selection.equals("\"*\"")){
            //mcursor=query_all();
            starstring=star_local();
            p=0;
            for(String sp:starstring.split("~")){
                String[] col=sp.split(",");
                mcursor.addRow(col);
                Log.v("Adding row to mcursor: ",col[0]+","+col[1]);
                p++;

            }

            Log.v("Added "+p," rows from local");

            for(String i:REMOTE_PORT){

                if(i.equals(String.valueOf(Integer.valueOf(portStr)*2))){
                    Log.v("Loop in local port "+i,"Skipping");
                }
                else{
                    //msg=*+ToAvd+FromAvd
                    String msg="*"+"_"+String.valueOf(Integer.valueOf(i)/2)+"_"+portStr;
                    fake_clienttask(msg);
                    Log.v("Sent fake clienttask to port",String.valueOf(Integer.valueOf(i)/2));

                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    p=0;
                    Log.v("Adding key-values from port "+String.valueOf(Integer.valueOf(i)/2),starstring);
                    for(String sp:starstring.split("~")){
                        String[] col=sp.split(",");
                        mcursor.addRow(col);
                        Log.v("Adding row to mcursor: ",col[0]+","+col[1]);
                        p++;

                    }
                    Log.v("Added "+p," rows from avd"+String.valueOf(Integer.valueOf(i)/2));

                }

            }


            return mcursor;


        }

        else if(!selection.equals("\"*\"") && !selection.equals("\"@\"")&& !portStr.equals(HashtoPort.get(CoordHash))){
            //message is the type + avd + key
            synchronized (lock2) {
                Log.v("Query not found in local case entered,calling client task to port", HashtoPort.get(CoordHash));
                String msg = "query" + "_" + HashtoPort.get(CoordHash) + "_" + selection + "_" + portStr;
                //now we have the port to where we need to send to
                //new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,msg);
                querywait=true;
                Log.v("Before routing,querywait is:",""+querywait);
                fake_clienttask(msg);
                Log.v("Sent a ClientTask to port ", HashtoPort.get(CoordHash));
                Log.v("The messagge sent to this port is:",msg);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }


                //now you need to receive the queries
                //querywait=true;

                while(querywait){
                    Log.v("Inside the wait while","");
                }
                //getContext().getApplicationContext();


                Log.v("the value obtained and its corresponding key is", querybuff + "," + selection);
                String[] row = {selection, querybuff};
                mcursor.addRow(row);
                return mcursor;
            }

        }


        else if(!selection.equals("\"*\"") && !selection.equals("\"@\"")&& portStr.equals(HashtoPort.get(CoordHash))) {
            //synchronized (lock2) {
            Log.v("Query found in local case entered", "");
            for (String f : getContext().fileList()) {
                if (selection.equals(f)) {
                    String val = localquery(selection);
                    Log.v("The returned value for key:" + selection + "is: ", val);
                    String[] row = {selection, val};
                    mcursor.addRow(row);
                }
            }
            return mcursor;
            //}

        }
        else {
            Log.v("Didnt go to to any of the query cases", "");
            return null;
        }


        //Log.v("at the end of query,the returned cursor is",mcursor.toString());
        //return mcursor;

    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        // TODO Auto-generated method stub
        return 0;
    }


    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {

        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            ServerSocket serverSocket = sockets[0];


            try {

                while (true) {
                    String s;
                    Socket socket = serverSocket.accept();
                    InputStreamReader ipstream = new InputStreamReader(socket.getInputStream());
                    BufferedReader reader = new BufferedReader(ipstream);
                    s = reader.readLine();

                    if (s.contains("query")) {

                        Log.v("A query request has been made in servertask", s);
                        //synchronized (lock2){
                        String[] sparts = s.split("_");
                        //String port = sparts[1];
                        String key = sparts[2];
                        String senderport=sparts[3];


                        String q=localquery(key);
                        Log.v("value is fetched and is",q);
                        String msg="qresponse"+"_"+senderport+"_"+q;
                        //new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,msg);
                        fake_clienttask(msg);
                        //}



                    }

                    else if(s.contains("insert")){
                        synchronized (lock1) {
                            Log.v("An insert request has been made in servertask", s);
                            String[] sparts = s.split("_");
                            ContentValues val = new ContentValues();
                            val.put("key", sparts[2]);
                            val.put("value", sparts[3]);
                            Log.v("inserting ", sparts[2] + sparts[3]);
                            insert(mUri, val);
                        }
                    }

                    else if(s.contains("qresponse")){
                        Log.v("in the query response case in servertask",s);
                        //synchronized (lock2) {
                        //Log.v("Querywait is",""+querywait);
                        Log.v("s is", s);
                        querybuff = s.split("_")[2];
                        querywait=false;
                        Log.v("After routing back,querywait is",""+querywait);

                        //}
                        //querywait=false;
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }


                    }
                    else if(s.contains("replica")) {
                        synchronized (lock1) {
                            try {
                                String filename = s.split("_")[2];
                                String string = s.split("_")[3];
                                Log.v("Replica insert at servertask:Inserting key: " + filename + " to coordinator:", s.split("_")[1]);
                                FileOutputStream outputStream;
                                outputStream = getContext().openFileOutput(filename, Context.MODE_PRIVATE);
                                outputStream.flush();
                                outputStream.write(string.getBytes());
                                outputStream.close();
                                Log.v("Inserting success: ", filename + " , " + string);
                                //kv.put(filename,string);

                            } catch (Exception e) {
                                Log.e("Error", "Key-Value Creation Failed");
                            }
                        }
                    }
                    else if(s.contains("*")){
                        Log.v("At Servertask for * in port",s.split("_")[1]+" from port"+s.split("_")[2]);
                        //String[] colnames ={"key","value"};
                        //MatrixCursor mcursor = new MatrixCursor(colnames);
                        //mcursor=query_all();
                        String q=star_local();

                        String msg="star-response"+"_"+s.split("_")[2]+"_"+q;
                        fake_clienttask(msg);
                        Log.v("star-response: routing back to port",s.split("_")[2]);

                    }
                    else if(s.contains("star-response")){
                        Log.v("At star-response",s);
                        starstring=s.split("_")[2];
                        Log.v("starstring is now filtered as",starstring);




                    }

                    else if(s.contains("recovery")){
                        Log.v(s.split("_")[1]," Said recovery");

                        String[] colnames ={"key","value"};
                        MatrixCursor mcursor = new MatrixCursor(colnames);

                        //synchronized(lock1) {
                        mcursor = (MatrixCursor) query(mUri, null, "\"*\"", null, null);

                        Log.v("Just assigned my matrix cursor for recovery", "Going to traverse through the matrix cursor");
                        //}

                        //synchronized(lock1) {
                        mcursor.moveToFirst();


                        while (!mcursor.isAfterLast()) {
                            Log.v("Traversing through the cursor", "");


                            String k = mcursor.getString(0);
                            String v = mcursor.getString(1);

                            Log.v("extracted key and value from the cursor, they are", k + " , " + v);
                            String khash = "";
                            try {
                                khash = genHash(k);
                            } catch (NoSuchAlgorithmException e) {
                                e.printStackTrace();
                            }
                            Log.v("Obtained hash of key ", k);
                            //block to find the positions of coordinator and previous 2 positions
                            String port = HashtoPort.get(findCoord(khash));
                            String previous1 = "";
                            String previous2 = "";
                            if (!port.equals("5556") && !port.equals("5562")) {
                                previous1 = HashtoPort.get(HashtoPort.lowerKey(findCoord(khash)));
                                previous2 = HashtoPort.get(HashtoPort.lowerKey(HashtoPort.lowerKey(findCoord(khash))));

                            } else if (port.equals("5556")) {
                                previous1 = HashtoPort.get(HashtoPort.lowerKey(findCoord(khash)));
                                previous2 = HashtoPort.get(HashtoPort.lastKey());
                            } else if (port.equals("5562")) {
                                previous1 = HashtoPort.get(HashtoPort.lastKey());
                                previous2 = HashtoPort.get(HashtoPort.lowerKey(HashtoPort.lastKey()));
                            }
                            Log.v("Found the coordinator,previous1 and previous2 positions for this key they are", port + "," + previous1 + "," + previous2);

                            if (port.equals(portStr) || previous1.equals(portStr) || previous2.equals(portStr)) {

                                localinsert(k, v);
                            }
                            Log.v("Finished inserting in ", portStr);

                            mcursor.moveToNext();
                            Log.v("Moved mcursor one position front", "");
                        }
                        //}
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }



                        //}



                    }


                    socket.close();

                }
            } catch (IOException e) {
                Log.e(TAG, "Reception Error");

            }


            return null;
        }
    }


    private class ClientTask extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... msgs) {
            try {


                String msgToSend = msgs[0];


                Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                        Integer.parseInt(msgToSend.split("_")[1])*2);

                Log.v("ClientTask is creating socket to port:",msgToSend.split("_")[1]);




                PrintWriter writer = new PrintWriter(socket.getOutputStream());
                writer.println(msgToSend);
                writer.flush();
                writer.close();
                socket.close();
                //}


            } catch (UnknownHostException e) {
                Log.e(TAG, "ClientTask UnknownHostException");
            } catch (IOException e) {
                Log.e(TAG, "ClientTask socket IOException");
            }

            return null;
        }
    }







    private String genHash(String input) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] sha1Hash = sha1.digest(input.getBytes());
        Formatter formatter = new Formatter();
        for (byte b : sha1Hash) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }

    private Uri buildUri(String scheme, String authority) {
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.authority(authority);
        uriBuilder.scheme(scheme);
        return uriBuilder.build();
    }

    private String findCoord(String selection) {
        int flag=0;
        String CoordinatorHash = "Nothing yet";
        for (String s : HashtoPort.keySet()) {
            if (selection.compareTo(s) <= 0) {

                CoordinatorHash = s;
                flag=1;
                Log.v("non corner case,coordinator is:", HashtoPort.get(CoordinatorHash));
                break;
            }

        }
        if (flag == 0) {
            CoordinatorHash = HashtoPort.firstKey();
            Log.v("corner case,coordinator is:" ,HashtoPort.get(CoordinatorHash));

        }
        Log.v("Rechecking,coordinator is:" ,HashtoPort.get(CoordinatorHash));
        return CoordinatorHash;
    }

    private String localquery(String selection){
        //String[] colnames ={"key","value"};
        //MatrixCursor mcursor = new MatrixCursor(colnames);
        String s="";
        try {
            //Log.e("Checking if portStr and Coordinator match for query",portStr+","+HashtoPort.get(CoordHash)+","+selection);
            FileInputStream inputStream = getContext().openFileInput(selection);
            InputStreamReader ipstream = new InputStreamReader(inputStream);
            BufferedReader reader = new BufferedReader(ipstream);
            s = reader.readLine();
            s.trim();

        } catch (IOException e) {
            e.printStackTrace();
            Log.e("Error", "Cant read file");

        }
        return s;
    }

    protected void fake_clienttask(String s) {
        try {
            //synchronized (lock2){

            String msgToSend = s;


            Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                    Integer.parseInt(msgToSend.split("_")[1]) * 2);

            Log.v("Fake_ClientTask is creating socket to port:", msgToSend.split("_")[1]);


            PrintWriter writer = new PrintWriter(socket.getOutputStream());
            writer.println(msgToSend);
            writer.flush();
            writer.close();
            socket.close();
            //}



        } catch (UnknownHostException e) {
            Log.e(TAG, "ClientTask UnknownHostException");
        } catch (IOException e) {
            Log.e(TAG, "ClientTask socket IOException");
        }
    }

    protected MatrixCursor query_all() {
        String[] colnames = {"key", "value"};
        MatrixCursor mcursor = new MatrixCursor(colnames);
        Log.v("In query @ method","");
        for (String file : getContext().fileList()) {
            try {

                FileInputStream inputStream = getContext().openFileInput(file);
                InputStreamReader ipstream = new InputStreamReader(inputStream);
                BufferedReader reader = new BufferedReader(ipstream);
                String s = reader.readLine();
                String[] row = {file, s};
                mcursor.addRow(row);
                Log.v("Added key",file+" to cursor");


            } catch (IOException e) {
                Log.e("Error", "Cant read file");
            }
        }

        return mcursor;
    }

    protected String star_local() {

        Log.v("In star_local method","");
        StringBuilder sb = new StringBuilder("");
        for (String file : getContext().fileList()) {


            try {

                FileInputStream inputStream = getContext().openFileInput(file);
                InputStreamReader ipstream = new InputStreamReader(inputStream);
                BufferedReader reader = new BufferedReader(ipstream);
                String s = reader.readLine();
                s.trim();
                //String[] row = {file, s};
                sb.append(file+","+s+"~");

                //mcursor.addRow(row);
                Log.v("appended "+file+","+s,"to sb");


            } catch (IOException e) {
                Log.e("Error", "Cant read file");
            }
        }
        Log.v("Returning sb as",sb.toString());
        return sb.toString();
    }

    protected void localinsert(String key,String value){
        try {
            Log.v("Local Insert Method:Inserting key: ",key );
            FileOutputStream outputStream;
            outputStream = getContext().openFileOutput(key, Context.MODE_PRIVATE);
            outputStream.flush();
            outputStream.write(value.getBytes());
            outputStream.close();
            Log.v("Inserting success: ", key+ " , " + value);

        } catch (Exception e) {
            Log.e("Error", "Key-Value Creation Failed");
        }
    }



}
