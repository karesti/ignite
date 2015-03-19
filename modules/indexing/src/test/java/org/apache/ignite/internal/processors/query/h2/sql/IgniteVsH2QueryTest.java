/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.h2.sql;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.affinity.*;
import org.apache.ignite.cache.query.*;
import org.apache.ignite.cache.query.annotations.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.marshaller.optimized.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.junits.common.*;

import java.io.*;
import java.sql.*;
import java.util.*;

import static org.apache.ignite.cache.CacheDistributionMode.*;

/**
 * Test to compare query results from h2 database instance and mixed ignite caches (replicated and partitioned) 
 * which have the same data models and data content. 
 */
public class IgniteVsH2QueryTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);
    
    /** Partitioned cache. */
    private static IgniteCache pCache;

    /** Replicated cache. */
    private static IgniteCache rCache;
    
    /** H2 db connection. */
    private static Connection conn;

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration c = super.getConfiguration(gridName);

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setIpFinder(IP_FINDER);

        c.setDiscoverySpi(disco);

        c.setMarshaller(new OptimizedMarshaller(true));

        c.setCacheConfiguration(createCache("partitioned", CacheMode.PARTITIONED),
            createCache("replicated", CacheMode.REPLICATED)
        );

        return c;
    }

    /**
     * Creates new cache configuration.
     *
     * @param name Cache name.
     * @param mode Cache mode.
     * @return Cache configuration.
     */
    private static CacheConfiguration createCache(String name, CacheMode mode) {
        CacheConfiguration<?,?> cc = defaultCacheConfiguration();

        cc.setName(name);
        cc.setCacheMode(mode);
        cc.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
        cc.setEvictNearSynchronized(false);
        cc.setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL);
        cc.setDistributionMode(PARTITIONED_ONLY);

        if (mode == CacheMode.PARTITIONED)
            cc.setIndexedTypes(
                Integer.class, Organization.class,
                CacheAffinityKey.class, Person.class,
                CacheAffinityKey.class, Purchase.class
            );
        else if (mode == CacheMode.REPLICATED)
            cc.setIndexedTypes(
                Integer.class, Product.class
            );
        else
            throw new IllegalStateException("mode: " + mode);

        return cc;
    }


    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        super.beforeTestsStarted();

        Ignite ignite = startGrids(4);

        pCache = ignite.jcache("partitioned");
        
        rCache = ignite.jcache("replicated");

        awaitPartitionMapExchange();
        
        conn = openH2Connection(false);

        initializeH2Schema();

        initCacheAndDbData();

        checkAllDataEquals();
    }
    
    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        super.afterTestsStopped();
        
        conn.close();
        
        stopAllGrids();
    }

    /**
     * Populate cache and h2 database with test data.
     */
    @SuppressWarnings("unchecked")
    private void initCacheAndDbData() throws SQLException {
        int idGen = 0;
        
        // Organizations.
        List<Organization> organizations = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            int id = idGen++;
            
            Organization org = new Organization(id, "Org" + id);
            
            organizations.add(org);
            
            pCache.put(org.id, org);
            
            insertInDb(org);
        }

        // Persons.
        List<Person> persons = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
            int id = idGen++;

            Organization org = organizations.get(i % organizations.size());
            
            Person person = new Person(id, org, "name" + id, "lastName" + id, id * 100.0);

            persons.add(person);

            pCache.put(new CacheAffinityKey<>(id, org.id), person);
            
            insertInDb(person);
        }

        // Products.
        List<Product> products = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            int id = idGen++;
            
            Product product = new Product(id, "Product" + id, id*1000);
            
            products.add(product);
            
            rCache.put(product.id, product);
            
            insertInDb(product);
        }

        // Purchases.
        for (int i = 0; i < products.size() * 2; i++) {
            int id = idGen++;

            Person person = persons.get(i % persons.size());
            
            Purchase purchase = new Purchase(id, products.get(i % products.size()), person);

            pCache.put(new CacheAffinityKey<>(id, person.id), purchase);
            
            insertInDb(purchase);
        }
    }

    /**
     * Insert {@link Organization} at h2 database.
     *  
     * @param org Organization.
     * @throws SQLException If exception.
     */
    private void insertInDb(Organization org) throws SQLException {
        try(PreparedStatement st = conn.prepareStatement("insert into ORGANIZATION (id, name) values(?, ?)")) {
            st.setInt(1, org.id);
            st.setString(2, org.name);

            st.executeUpdate();
        }
    }

    /**
     * Insert {@link Person} at h2 database.
     *
     * @param p Person.
     * @throws SQLException If exception.
     */
    private void insertInDb(Person p) throws SQLException {
        try(PreparedStatement st = conn.prepareStatement(
            "insert into PERSON (id, firstName, lastName, orgId, salary) values(?, ?, ?, ?, ?)")) {
            st.setInt(1, p.id);
            st.setString(2, p.firstName);
            st.setString(3, p.lastName);
            st.setInt(4, p.orgId);
            st.setDouble(5, p.salary);

            st.executeUpdate();
        }
    }

    /**
     * Insert {@link Product} at h2 database.
     *
     * @param p Product.
     * @throws SQLException If exception.
     */
    private void insertInDb(Product p) throws SQLException {
        try(PreparedStatement st = conn.prepareStatement("insert into PRODUCT (id, name, price) values(?, ?, ?)")) {
            st.setInt(1, p.id);
            st.setString(2, p.name);
            st.setInt(3, p.price);

            st.executeUpdate();
        }
    }

    /**
     * Insert {@link Purchase} at h2 database.
     *
     * @param p Purchase.
     * @throws SQLException If exception.
     */
    private void insertInDb(Purchase p) throws SQLException {
        try(PreparedStatement st = conn.prepareStatement(
            "insert into PURCHASE (id, personId, productId) values(?, ?, ?)")) {
            st.setInt(1, p.id);
            st.setInt(2, p.personId);
            st.setInt(3, p.productId);

            st.executeUpdate();
        }
    }

    /**
     * Initialize h2 database schema.
     *
     * @throws SQLException If exception.
     */
    private void initializeH2Schema() throws SQLException {
        Statement st = conn.createStatement();
        
        st.execute("create table ORGANIZATION" +
            "  (id int unique," +
            "  name varchar(255))");
        
        st.execute("create table PERSON" +
            "  (id int unique, " +
            "  firstName varchar(255), " +
            "  lastName varchar(255)," +
            "  orgId int not null," +
            "  salary double )");

        st.execute("create table PRODUCT" +
            "  (id int unique, " +
            "  name varchar(255), " +
            "  price int)");

        st.execute("create table PURCHASE" +
            "  (id int unique, " +
            "  personId int, " +
            "  productId int)");

        conn.commit();
    }

    /**
     * Gets connection from a pool.
     *
     * @param autocommit {@code true} If connection should use autocommit mode.
     * @return Pooled connection.
     * @throws SQLException In case of error.
     */
    private Connection openH2Connection(boolean autocommit) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:h2:mem:example;DB_CLOSE_DELAY=-1");

        conn.setAutoCommit(autocommit);

        return conn;
    }

    /**
     * Execute given sql query on h2 database and on partitioned ignite cache and compare results.
     *
     * @param sql SQL query.
     * @param args SQL arguments.
     * then results will compare as ordered queries.
     * @throws SQLException If exception.
     */
    private void testQuery(String sql, Object... args) throws SQLException {
        testQuery(pCache, sql, args, Order.RANDOM);
    }

    /**
     * Execute given sql query on h2 database and on ignite cache and compare results. 
     * Expected that results are not ordered.
     *
     * @param cache Ignite cache.
     * @param sql SQL query.
     * @param args SQL arguments.
     * then results will compare as ordered queries.
     * @throws SQLException If exception.
     */
    private void testQuery(IgniteCache cache, String sql, Object... args) throws SQLException {
        testQuery(cache, sql, args, Order.RANDOM);
    }

    /**
     * Execute given sql query on h2 database and on partitioned ignite cache and compare results.
     * Expected that results are ordered.
     *
     * @param sql SQL query.
     * @param args SQL arguments.
     * then results will compare as ordered queries.
     * @throws SQLException If exception.
     */
    private void testOrderedQuery(String sql, Object... args) throws SQLException {
        testQuery(pCache, sql, args, Order.ORDERED);
    }

    /**
     * Execute given sql query on h2 database and on ignite cache and compare results.
     *
     * @param cache Ignite cache.
     * @param sql SQL query.
     * @param args SQL arguments.
     * @param order Expected ordering of SQL results. If {@link Order#ORDERED} 
     * then results will compare as ordered queries.
     * @throws SQLException If exception.
     */
    @SuppressWarnings("unchecked")
    private void testQuery(IgniteCache cache, String sql, Object[] args, Order order) throws SQLException {
        log.info("Sql=" + sql + ", args=" + Arrays.toString(args));

        List<List<?>> h2Res = executeH2Query(sql, args);

        List<List<?>> cacheRes = cache.queryFields(new SqlFieldsQuery(sql).setArgs(args)).getAll();

        assertRsEquals(h2Res, cacheRes, order);
    }

    /**
     * Execute SQL query on h2 database.
     *
     * @param sql SQL query.
     * @param args SQL arguments.
     * @return Result of SQL query on h2 database.
     * @throws SQLException If exception.
     */
    private List<List<?>> executeH2Query(String sql, Object[] args) throws SQLException {
        List<List<?>> res = new ArrayList<>();
        ResultSet rs = null;

        try(PreparedStatement st = conn.prepareStatement(sql)) {
            for (int idx = 0; idx < args.length; idx++)
                st.setObject(idx + 1, args[idx]);

            rs = st.executeQuery();

            int colCnt = rs.getMetaData().getColumnCount();

            while (rs.next()) {
                List<Object> row = new ArrayList<>(colCnt);
                
                for (int i = 1; i <= colCnt; i++)
                    row.add(rs.getObject(i));
                
                res.add(row);
            }
        }
        finally {
            U.closeQuiet(rs);
        }

        return res;
    }

    /**
     * Assert equals of result sets according to expected ordering.
     *
     * @param rs1 Expected result set.
     * @param rs2 Actual result set.
     * @param order Expected ordering of SQL results. If {@link Order#ORDERED} 
     * then results will compare as ordered queries.
     */
    private void assertRsEquals(List<List<?>> rs1, List<List<?>> rs2, Order order) {
        assertEquals("Rows count has to be equal.", rs1.size(), rs2.size());
        
        switch (order){
            case ORDERED:
                for (int rowNum = 0; rowNum < rs1.size(); rowNum++) {
                    List<?> row1 = rs1.get(rowNum);
                    List<?> row2 = rs2.get(rowNum);

                    assertEquals("Columns count have to be equal.", row1.size(), row2.size());

                    for (int colNum = 0; colNum < row1.size(); colNum++)
                        assertEquals("Row=" + rowNum + ", column=" + colNum, row1.get(colNum), row2.get(colNum));
                }

                break;
            case RANDOM:
                for (List<?> row1 : rs1)
                    assertTrue("Actual result set has to contain row.\n" + "Result set=" + rs2 + "\n" + "Row=" + row1, 
                        rs2.contains(row1));
                
                break;
            default: 
                throw new IllegalStateException();
        }
    }

    /**
     * @throws Exception If failed.
     */
    private void checkAllDataEquals() throws Exception {
        testQuery("select id, name from Organization");

        testQuery("select id, firstName, lastName, orgId, salary from Person");

        testQuery("select id, personId, productId from Purchase");

        testQuery(rCache, "select id, name, price from Product");
    }

    /**
     * @throws Exception If failed.
     */
    public void testEmptyResult() throws Exception {
        testQuery("select id from Person where 0 = 1");
    }

    /**
     * @throws Exception If failed.
     */
    public void testSelectWithStar() throws Exception {
        testQuery("select * from Person");
    }

    /**
     * @throws Exception If failed.
     */
    public void testSelectWithStar2() throws Exception {
        testQuery("select Person.* from Person");
    }

    /**
     * @throws Exception If failed.
     */
    public void testSqlQueryWithAggregation() throws Exception {
        testQuery("select avg(salary) from Person, Organization where Person.orgId = Organization.id and "
            + "lower(Organization.name) = lower(?)", "Org1");
    }

    /**
     * @throws Exception If failed.
     */
    public void testSqlFieldsQuery() throws Exception {
        testQuery("select concat(firstName, ' ', lastName) from Person");
    }

    /**
     * @throws Exception If failed.
     */
    public void testSqlFieldsQueryWithJoin() throws Exception {
        testQuery("select concat(firstName, ' ', lastName), "
            + "Organization.name from Person, Organization where "
            + "Person.orgId = Organization.id");
    }

    /**
     * @throws Exception If failed.
     */
    public void testOrdered() throws Exception {
        testOrderedQuery("select firstName, lastName" +
                " from Person" +
                " order by lastName, firstName"
        );
    }

    /**
     * //TODO Investigate.
     *  
     * @throws Exception If failed.
     */
    public void testSimpleJoin() throws Exception {
        // Have expected results.
        testQuery("select id, firstName, lastName" +
            "  from Person" +
            "  where Person.id = ?", 3);

        // Ignite cache return 0 results...
        testQuery("select Person.firstName" +
            "  from Person, Purchase" +
            "  where Person.id = ?", 3);
    }

    /**
     * @throws Exception If failed.
     */
    public void testSimpleReplicatedSelect() throws Exception {
        testQuery(rCache, "select id, name from Product");
    }

    /**
     * @throws Exception If failed.
     */
    public void testCrossCache() throws Exception {
        //TODO Investigate (should be 20 results instead of 0).
        testQuery("select firstName, lastName" +
            "  from Person, Purchase" +
            "  where Person.id = Purchase.personId");

        //TODO Investigate.
        testQuery("select concat(firstName, ' ', lastName), Product.name " +
            "  from Person, Purchase, \"replicated\".Product " +
            "  where Person.id = Purchase.personId and Purchase.productId = Product.id" +
            "  group by Product.id");

        //TODO Investigate.
        testQuery("select concat(firstName, ' ', lastName), count (Product.id) " +
            "  from Person, Purchase, \"replicated\".Product " +
            "  where Person.id = Purchase.personId and Purchase.productId = Product.id" +
            "  group by Product.id");
    }

    /**
     * Person class. Stored at partitioned cache.
     */
    private static class Person implements Serializable {
        /** Person ID (indexed). */
        @QuerySqlField(index = true)
        private int id;

        /** Organization ID (indexed). */
        @QuerySqlField(index = true)
        private int orgId;

        /** First name (not-indexed). */
        @QuerySqlField
        private String firstName;

        /** Last name (not indexed). */
        @QuerySqlField
        private String lastName;

        /** Salary (indexed). */
        @QuerySqlField(index = true)
        private double salary;

        /**
         * Constructs person record.
         *
         * @param org Organization.
         * @param firstName First name.
         * @param lastName Last name.
         * @param salary Salary.
         */
        Person(int id, Organization org, String firstName, String lastName, double salary) {
            this.id = id;
            this.firstName = firstName;
            this.lastName = lastName;
            this.salary = salary;
            orgId = org.id;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "Person [firstName=" + firstName +
                ", lastName=" + lastName +
                ", id=" + id +
                ", orgId=" + orgId +
                ", salary=" + salary + ']';
        }
    }

    /**
     * Organization class. Stored at partitioned cache.
     */
    private static class Organization implements Serializable {
        /** Organization ID (indexed). */
        @QuerySqlField(index = true)
        private int id;

        /** Organization name (indexed). */
        @QuerySqlField(index = true)
        private String name;

        /**
         * Create Organization.
         *
         * @param id Organization ID.
         * @param name Organization name.
         */
        Organization(int id, String name) {
            this.id = id;
            this.name = name;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "Organization [id=" + id + ", name=" + name + ']';
        }
    }

    /**
     * Product class. Stored at replicated cache.
     */
    private static class Product implements Serializable {
        /** Primary key. */
        @QuerySqlField(index = true)
        private int id;

        /** Product name. */
        @QuerySqlField
        private String name;

        /** Product price */
        @QuerySqlField
        private int price;

        /**
         * Create Product.
         *
         * @param id Product ID.
         * @param name Product name.
         * @param price Product price.
         */
        Product(int id, String name, int price) {
            this.id = id;
            this.name = name;
            this.price = price;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "Product [id=" + id + ", name=" + name + ", price=" + price + ']';
        }
    }

    /**
     * Purchase class. Stored at partitioned cache.
     */
    private static class Purchase implements Serializable {
        /** Primary key. */
        @QuerySqlField(index = true)
        private int id;

        /** Product ID. */
        @QuerySqlField
        private int productId;

        /** Person ID. */
        @QuerySqlField
        private int personId;

        /**
         * Create Purchase.
         *
         * @param id Purchase ID.
         * @param product Purchase product.
         * @param person Purchase person.
         */
        Purchase(int id, Product product, Person person) {
            this.id = id;
            productId = product.id;
            personId = person.id;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "Purchase [id=" + id + ", productId=" + productId + ", personId=" + personId + ']';
        }
    }

    /**
     * Order type. 
     */
    private enum Order {
        /** Random. */
        RANDOM, 
        /** Ordered. */
        ORDERED
    }
}
