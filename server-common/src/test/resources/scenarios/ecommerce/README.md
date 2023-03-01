### About

This is the "ecommerce" sample dataset from OpenSearch.

### Create Table

* TODO: this currently uses nested Tuples, which Hydrolix doesn't support yet

```json
{
    "category": [
        "Men's Clothing"
    ],
    "currency": "EUR",
    "customer_first_name": "Eddie",
    "customer_full_name": "Eddie Underwood",
    "customer_gender": "MALE",
    "customer_id": 38,
    "customer_last_name": "Underwood",
    "customer_phone": "",
    "day_of_week": "Monday",
    "day_of_week_i": 0,
    "email": "eddie@underwood-family.zzz",
    "manufacturer": [
        "Elitelligence",
        "Oceanavigations"
    ],
    "order_date": "2016-12-26T09:28:48+00:00",
    "order_id": 584677,
    "products": [
        {
            "base_price": 11.99,
            "discount_percentage": 0,
            "quantity": 1,
            "manufacturer": "Elitelligence",
            "tax_amount": 0,
            "product_id": 6283,
            "category": "Men's Clothing",
            "sku": "ZO0549605496",
            "taxless_price": 11.99,
            "unit_discount_amount": 0,
            "min_price": 6.35,
            "_id": "sold_product_584677_6283",
            "discount_amount": 0,
            "created_on": "2016-12-26T09:28:48+00:00",
            "product_name": "Basic T-shirt - dark blue/white",
            "price": 11.99,
            "taxful_price": 11.99,
            "base_unit_price": 11.99
        },
        {
            "base_price": 24.99,
            "discount_percentage": 0,
            "quantity": 1,
            "manufacturer": "Oceanavigations",
            "tax_amount": 0,
            "product_id": 19400,
            "category": "Men's Clothing",
            "sku": "ZO0299602996",
            "taxless_price": 24.99,
            "unit_discount_amount": 0,
            "min_price": 11.75,
            "_id": "sold_product_584677_19400",
            "discount_amount": 0,
            "created_on": "2016-12-26T09:28:48+00:00",
            "product_name": "Sweatshirt - grey multicolor",
            "price": 24.99,
            "taxful_price": 24.99,
            "base_unit_price": 24.99
        }
    ],
    "sku": [
        "ZO0549605496",
        "ZO0299602996"
    ],
    "taxful_total_price": 36.98,
    "taxless_total_price": 36.98,
    "total_quantity": 2,
    "total_unique_products": 2,
    "type": "order",
    "user": "eddie",
    "geoip": {
        "country_iso_code": "EG",
        "location": {
            "lon": 31.3,
            "lat": 30.1
        },
        "region_name": "Cairo Governorate",
        "continent_name": "Africa",
        "city_name": "Cairo"
    },
    "event": {
        "dataset": "sample_ecommerce"
    }
}

```

```sql
create table opensearch_sample_data.ecommerce (
    category Array(Varchar(64)),
    currency Varchar(8),
    customer_first_name Varchar(64),
    customer_full_name Varchar(128),
    customer_gender Varchar(16),
    customer_id Int64,
    customer_last_name Varchar(64),
    customer_phone Varchar(32),
    day_of_week Varchar(16),
    day_of_week_i Int16,
    email Varchar(128),
    manufacturer Array(Varchar(128)),
    order_date Varchar(64),
    order_id Int64,
    products Nested(
      base_price Float64,
      discount_percentage Float64,
      quantity Int64,
      manufacturer Varchar(128),
      tax_amount Float64,
      product_id Int64,
      category Varchar(128),
      sku Varchar(64),
      taxless_price Float64,
      unit_discount_amount Float64,
      min_price Float64,
      _id varchar(128),
      discount_amount Float64,
      created_on Varchar(64),
      product_name Varchar(128),
      price Float64,
      taxful_price Float64,
      base_unit_price Float64
    ),
    sku Array(Varchar(64)),
    taxful_total_price Float64,
    taxless_total_price Float64,
    total_quantity Int64,
    total_unique_products Int64,
    type Varchar(32),
    user Varchar(32),
    geoip Nested(
      country_iso_code Varchar(8),
      location Nested(
        lon Float64,
        lat Float64
      ),
      continent_name Varchar(64),
      region_name Nullable(Varchar(128)),
      city_name Nullable(Varchar(64))
    ),
    event Tuple(
      dataset Varchar(32)
    ),
    time timestamp materialized parseDateTime64BestEffort(order_date)
) engine=MergeTree order by time;
```