-- System test 01: catalog and statistics meta-commands on the sample database.
-- Run:    scripts/run.sh client --echo --file=test-scenarios/system/01-catalog.sql
-- Needs:  a server started with --seed. Read-only.
-- Exit:   0

-- expect: customers, order_items, orders
\dt

-- expect: customer_id integer PK; name varchar(100); credit float nullable; status char(1);
--         index idx_customers_name (name, hash); no foreign keys section
\d customers

-- expect: 4 columns; index idx_orders_customer_id (customer_id, b+tree);
--         foreign key fk_orders_customer: customer_id -> customers(customer_id), on delete restrict
\d orders

-- expect: foreign key fk_items_order: order_id -> orders(order_id), on delete restrict
\d order_items

-- expect: 3 indexes: idx_customers_name hash, idx_order_items_order_id b+tree, idx_orders_customer_id b+tree
\di

-- expect: only idx_orders_customer_id
\di orders

-- expect: one row per table, each with n_r = 60
\stats

-- expect: n_r = 60, b_r = 1; V(A,r) = 60 for every column
\stats orders
