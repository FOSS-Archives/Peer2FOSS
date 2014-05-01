package peergos.corenode;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;

import java.util.*;
import java.net.*;
import java.sql.*;

import org.bouncycastle.util.encoders.Base64;

public class SQLiteCoreNode extends AbstractCoreNode 
{

    private static final String TABLE_NAMES_SELECT_STMT = "SELECT * FROM sqlite_master WHERE type='table';";
    private static final String CREATE_USERS_TABLE = "create table users (id integer primary key autoincrement, name text not null, publickey text not null);";
    private static final String CREATE_FOLLOW_REQUESTS_TABLE = "create table followrequests (id integer primary key autoincrement, name text not null, publickey text not null);";
    private static final String CREATE_SHARING_KEYS_TABLE = "create table sharingkeys (id integer primary key autoincrement, name text not null, publickey text not null);";
    private static final String CREATE_FRAGMENTS_TABLE = "create table fragments (id integer primary key autoincrement, sharingkeyid not null, mapkey text not null, fragmentdata text not null);";
    private static final String CREATE_STORAGE_TABLE = "create table storage (id integer primary key autoincrement, address text not null, port integer not null);";

    private static final Map<String,String> TABLES = new HashMap<String,String>();
    static
    {
        TABLES.put("users", CREATE_USERS_TABLE);
        TABLES.put("followrequests", CREATE_FOLLOW_REQUESTS_TABLE);
        TABLES.put("sharingkeys", CREATE_SHARING_KEYS_TABLE);
        TABLES.put("fragments", CREATE_FRAGMENTS_TABLE);
        TABLES.put("storage", CREATE_STORAGE_TABLE);
    } 

    abstract static class RowData
    {
        public final String name;
        public final byte[] data;
        public final String b64string;
        RowData(String name, byte[] data)
        {
            this(name,data,(data == null ? null: new String(Base64.encode(data))));
        }

        RowData(String name, String d)
        {
            this(name, Base64.decode(d), d);
        }
        RowData(String name, byte[] data, String b64string)
        {
            this.name = name;
            this.data = data;
            this.b64string = b64string;
        }


        abstract String b64DataName();
        abstract String insertStatement();
        abstract String selectStatement();
        abstract String deleteStatement();

        public boolean insert() 
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement(insertStatement());
                stmt.setString(1,this.name);
                stmt.setString(2,this.b64string);
                stmt.executeUpdate();
                conn.commit();
                return true;
            } catch (SQLException sqe) {
                sqe.printStackTrace();
                return false;
            } finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) {
                        sqe2.printStackTrace();
                    }
            }
        }

        public RowData[] select()
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement(selectStatement());
                ResultSet rs = stmt.executeQuery();
                List<RowData> list = new ArrayList<RowData>();
                while (rs.next())
                {
                    String username = rs.getString("name");
                    String b64string = rs.getString(b64DataName());
                    list.add(new UserData(username, b64string));
                }
                return list.toArray(new RowData[0]);
            } catch (SQLException sqe) { 
                sqe.printStackTrace();
                return null;
            }finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) { 
                        sqe2.printStackTrace();
                    }
            }
        }


        public boolean delete() 
        {
            Statement stmt = null;
            try
            {
                stmt = conn.createStatement();
                stmt.executeUpdate(deleteStatement());
                conn.commit();
                return true;
            } catch (SQLException sqe) { 
                sqe.printStackTrace();
                return false;
            } finally { 
                if (stmt != null)
                    try 
                    {
                        stmt.close();
                    } catch (SQLException sqe2) { 
                        sqe2.printStackTrace();
                    }
            }
        }

        protected static Connection conn;
        public static void setConnection(Connection conn){RowData.conn = conn;}
    }

    static class UserData extends RowData
    {
        UserData(String name, byte[] publicKey)
        {
            super(name, publicKey);
        }
        UserData(String name, String d)
        {
            super(name, d);
        }

        public String b64DataName(){return DATA_NAME;}
        public String insertStatement(){return "insert into users (name, publickey) VALUES(?, ?);";}
        public String selectStatement(){return "select name, "+b64DataName()+" from users where name = '"+name+"';";}
        public String deleteStatement(){return "delete from users where name = "+ name +" and "+ b64DataName()+ " = "+ b64string + ";";}
        static final String DATA_NAME = "publickey";
        
        static int getID(String name)
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("select id from users where name = '"+ name+"';");
                ResultSet rs = stmt.executeQuery();
                int id = -1;
                while(rs.next())
                    id = rs.getInt("id");
                return id;
            } catch (SQLException sqe) { 
                sqe.printStackTrace();
                return -1;
            } finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) { 
                        sqe2.printStackTrace();
                    }
            }
        }
    }

    static class FollowRequestData extends RowData
    {
        FollowRequestData(String name, byte[] publicKey)
        {
            super(name, publicKey);
        }
        FollowRequestData(String name, String d)
        {
            super(name, d);
        }

        public String b64DataName(){return DATA_NAME;}
        public String insertStatement(){return "insert into followrequests (name, publickey) VALUES(?, ?);";}
        public String selectStatement(){return "select name, "+b64DataName()+" from followrequests where name = "+name+";";}
        public String deleteStatement(){return "delete from followrequests where name = "+ name +" and "+ b64DataName()+ " = "+ b64string + ";";}
        static final String DATA_NAME = "publickey";
    }

    static class SharingKeyData extends RowData
    {
        SharingKeyData(String name, byte[] publicKey)
        {
            super(name, publicKey);
        }
        SharingKeyData(String name, String d)
        {
            super(name, d);
        }
        SharingKeyData(String name, byte[] publicKey, String b64publicKey)
        {
            super(name, publicKey, b64publicKey);
        }

        public String b64DataName(){return DATA_NAME;}
        public String insertStatement(){return "insert into sharingkeys (name, publickey) VALUES(?, ?);";}
        public String selectStatement(){return "select name, "+b64DataName()+" from sharingkeys where name = "+name+";";}
        public String deleteStatement(){return "delete from sharingkeys where name = "+ name +" and "+ b64DataName()+ " = "+ b64string + ";";}
        static final String DATA_NAME = "publickey";

        static int getID(String name, byte[] sharingKey)
        {
            String b64sharingKey= new String(Base64.encode(sharingKey));

            PreparedStatement stmt = null;
            try
            {
                String s = "select id from sharingkeys where name = '"+ name + "' and publickey = '"+ b64sharingKey+"';";
                //System.out.println(s);
                stmt = conn.prepareStatement(s);
                ResultSet rs = stmt.executeQuery();
                int id = -1;
                while(rs.next())
                    id = rs.getInt("id");
                return id;
            } catch (SQLException sqe) { 
                sqe.printStackTrace();
                return -1;
            } finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) { 
                        sqe2.printStackTrace();
                    }
            }
        }
    }

    static class FragmentData 
    {
        final byte[] mapkey, fragmentdata;
        final String b64mapkey, b64fragmentdata;
        final int sharingKeyID;

        FragmentData(int sharingKeyID, byte[] mapkey, byte[] fragmentdata)
        {
            this(sharingKeyID, mapkey, new String(Base64.encode(mapkey)), fragmentdata, new String(Base64.encode(fragmentdata)));

        }

        FragmentData(int sharingKeyID, String b64mapkey, String b64fragmentData)
        {
            this(sharingKeyID, Base64.decode(b64mapkey), b64mapkey, Base64.decode(b64fragmentData), b64fragmentData);
        }

        FragmentData(int sharingKeyID, byte[] mapkey, String b64mapkey, byte[] fragmentdata, String b64fragmentdata)
        {
            this.sharingKeyID = sharingKeyID;
            this.mapkey=  mapkey;
            this.b64mapkey = b64mapkey;
            this.fragmentdata = fragmentdata;
            this.b64fragmentdata = b64fragmentdata;
        }

        public String selectStatement(){return "select sharingkeyid, mapkey fragmentdata from fragments where sharingkeyid = "+sharingKeyID +";";}
        public String deleteStatement(){return "delete from fragments where sharingkeyid = "+ sharingKeyID +";";}
        
        public boolean insert()
        {
            //int id = new SharingKeyData(name, publickey, b64string).getID();
            //if (id <0)
            //    return false;
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("insert into fragments (sharingkeyid, mapkey, fragmentdata) VALUES(?, ?, ?);");

                stmt.setInt(1,this.sharingKeyID);
                stmt.setString(2,this.b64mapkey);
                stmt.setString(3,this.b64fragmentdata);
                stmt.executeUpdate();
                conn.commit();
                return true;
            } catch (SQLException sqe) {
                sqe.printStackTrace();
                return false;
            } finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) {
                        sqe2.printStackTrace();
                    }
            }
        }
       
        public FragmentData selectOne()
        {
            FragmentData[] fd = select("where sharingKeyID = "+ sharingKeyID +" and mapkey = '"+ b64mapkey +"' and fragmentdata = '"+ b64fragmentdata+"'");
            if (fd == null || fd.length != 1)
                return null;
            return fd[0];
        }
        
        public static boolean deleteOne(String name, int sharingKeyID, String b64mapkey)
        {
            return delete("name = "+ name +" and sharingKeyID = "+ sharingKeyID +" and mapkey = "+ b64mapkey);
        }

        public static boolean delete(String deleteString)
        {
            Statement stmt = null;
            try
            {
                stmt = conn.createStatement();
                stmt.executeUpdate("delete from fragments where "+ deleteString +";");
                conn.commit();
                return true;
            } catch (SQLException sqe) { 
                sqe.printStackTrace();
                return false;
            } finally { 
                if (stmt != null)
                    try 
                    {
                        stmt.close();
                    } catch (SQLException sqe2) { 
                        sqe2.printStackTrace();
                    }
            }
        }

        public static boolean deleteAllByName(String username)
        {
            return delete("name = "+ username);
        }

        public static FragmentData[] selectAllByName(String username)
        {
            return select("where name = "+ username);
        }
        
        public static FragmentData[] select(String selectString)
        {
            PreparedStatement stmt = null;
            try
            {
                stmt = conn.prepareStatement("select sharingKeyID, mapkey, fragmentdata from fragments "+ selectString + ";");
                ResultSet rs = stmt.executeQuery();
                List<FragmentData> list = new ArrayList<FragmentData>();
                while (rs.next())
                {
                    FragmentData f = new FragmentData(rs.getInt("sharingkeyid"), rs.getString("mapkey"), rs.getString("fragmentdata"));
                    list.add(f);
                }
                
                return list.toArray(new FragmentData[0]);
            } catch (SQLException sqe) {
                sqe.printStackTrace();
                return null;
            } finally {
                if (stmt != null)
                    try
                    {
                        stmt.close();
                    } catch (SQLException sqe2) {
                        sqe2.printStackTrace();
                    }
            }
        }
       
       static Connection conn;
       static void setConnection(Connection conn){FragmentData.conn = conn;} 
    }

    private final String dbPath;
    private final Connection conn;
    private volatile boolean isClosed;

    public SQLiteCoreNode(String dbPath) throws SQLException 
    {
        this.dbPath = dbPath;
        try
        {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException cnfe) {
            throw new SQLException(cnfe);
        }

        String url = "jdbc:sqlite:"+dbPath;
        this.conn= DriverManager.getConnection(url);
        this.conn.setAutoCommit(false);
        RowData.setConnection(conn);
        FragmentData.setConnection(conn);

        init();
    }

    private synchronized void init() throws SQLException
    {
        if (isClosed)
            return;

        //do tables exists?
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(TABLE_NAMES_SELECT_STMT);

        ArrayList<String> missingTables = new ArrayList(TABLES.keySet());
        while (rs.next()) 
        {
            String tableName = rs.getString("name");
            missingTables.remove(tableName);
        }

        for (String missingTable: missingTables)
        {
            try
            {
                Statement createStmt = conn.createStatement();
                //System.out.println("Adding table "+ missingTable);
                createStmt.executeUpdate(TABLES.get(missingTable));
                createStmt.close();
                conn.commit();
            } catch ( Exception e ) {
                System.err.println( e.getClass().getName() + ": " + e.getMessage() );
            }
        }
    }

    public UserPublicKey getPublicKey(String username)
    {
        byte[] dummy = null;
        UserData user = new UserData(username, dummy);
        RowData[] users = user.select();
        if (users == null || users.length != 1)
            return null;
        return new UserPublicKey(users[0].data);
    }

    public boolean addUsername(String username, byte[] encodedUserKey, byte[] signedHash)
    {
        if (! super.addUsername(username, encodedUserKey, signedHash))
            return false;

        UserData user = new UserData(username, encodedUserKey); 
        return user.insert();
    }

    public boolean removeUsername(String username, byte[] userKey, byte[] signedHash)
    {
        if (! super.removeUsername(username, userKey, signedHash))
            return false;

        UserData user = new UserData(username, userKey);
        RowData[] rs = user.select();
        if (rs == null || rs.length ==0)
            return false;

        return user.delete();

    }

    public boolean followRequest(String target, byte[] encodedSharingPublicKey)
    {
        if (! super.followRequest(target, encodedSharingPublicKey))
            return false;

        FollowRequestData request = new FollowRequestData(target, encodedSharingPublicKey);
        return request.insert();
    }

    public boolean removeFollowRequest(String target, byte[] data, byte[] signedHash)
    {
        if (! super.removeFollowRequest(target, data, signedHash))
            return false;

        FollowRequestData request = new FollowRequestData(target, data);
        return request.delete();
    }

    public boolean allowSharingKey(String username, byte[] encodedSharingPublicKey, byte[] signedHash)
    {
        if (! super.allowSharingKey(username, encodedSharingPublicKey, signedHash))
            return false;

        SharingKeyData request = new SharingKeyData(username, encodedSharingPublicKey);
        return request.insert();
    }



    public boolean banSharingKey(String username, byte[] encodedsharingPublicKey, byte[] signedHash)
    {
        if (! super.banSharingKey(username, encodedsharingPublicKey, signedHash))
            return false;

        SharingKeyData request = new SharingKeyData(username, encodedsharingPublicKey);
        return request.delete();
    }

    public boolean addMetadataBlob(String username, byte[] encodedSharingPublicKey, byte[] mapKey, byte[] metadataBlob, byte[] sharingKeySignedHash)
    {
        if (! super.addMetadataBlob(username, encodedSharingPublicKey, mapKey, metadataBlob, sharingKeySignedHash))
            return false;
        
        int sharingKeyID = SharingKeyData.getID(username, encodedSharingPublicKey);
        if (sharingKeyID <0)
            return false;

        FragmentData fragment = new FragmentData(sharingKeyID, mapKey, metadataBlob);
        return fragment.insert();
    }

    public boolean removeMetadataBlob(String username, byte[] encodedSharingKey, byte[] mapKey, byte[] sharingKeySignedMapKey)
    {
        if (! super.removeMetadataBlob(username, encodedSharingKey, mapKey, sharingKeySignedMapKey))
            return false;

        int sharingKeyID = SharingKeyData.getID(username, encodedSharingKey);
        if (sharingKeyID <0)
            return false;
        return FragmentData.deleteOne(username, sharingKeyID, new String(Base64.encode(mapKey)));
    }

    public Iterator<UserPublicKey> getSharingKeys(String username)
    {
        return super.getSharingKeys(username); 
    }

    public MetadataBlob getMetadataBlob(String username, byte[] encodedSharingKey, byte[] mapkey)
    {
        return super.getMetadataBlob(username, encodedSharingKey, mapkey);
    }

    public boolean registerFragmentStorage(String spaceDonor, InetSocketAddress node, String owner, byte[] encodedSharingKey, byte[] hash, byte[] signedKeyPlusHash)
    {
        if (! super.registerFragmentStorage(spaceDonor, node, owner, encodedSharingKey, hash, signedKeyPlusHash))
            return false;
        return true; 

        //int userID = UserData.getID(recipient);
        //if (userID <0)
        //    return false;
    }

    public long getQuota(String user)
    {
        return super.getQuota(user); 
    }

    public long getUsage(String username)
    {
        return super.getUsage(username); 
    }

    public synchronized void close()     
    {
        if (isClosed)
            return;
        try
        {
            if (conn != null)
                conn.close();
            isClosed = true;
        } catch (Exception e) { 
            e.printStackTrace();
        }
    }
}
