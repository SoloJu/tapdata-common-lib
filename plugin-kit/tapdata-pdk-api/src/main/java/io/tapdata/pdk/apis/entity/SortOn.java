package io.tapdata.pdk.apis.entity;

public class SortOn {
    public static final int ASCENDING = 1;
    public static final int DESCENDING = 2;
    public static final int DEFAULT_NULL_SORT = 0;
    public static final int NULLS_FIRST = 1;
    public static final int NULLS_LAST = 2;
    private String key;
    private int sort;
    private int nullSort;

    public SortOn() {}

    public SortOn(String key, int sort) {
        this.key = key;
        this.sort = sort;
    }

    public SortOn(String key, int sort, int nullSort) {
        this.key = key;
        this.sort = sort;
        this.nullSort = nullSort;
    }

    public static SortOn ascending(String key) {
        return new SortOn(key, ASCENDING);
    }

    public static SortOn descending(String key) {
        return new SortOn(key, DESCENDING);
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public int getSort() {
        return sort;
    }

    public void setSort(int sort) {
        this.sort = sort;
    }
    public int getNullSort() {
        return nullSort;
    }

    public void setNullSort(int nullSort) {
        this.nullSort = nullSort;
    }

    public String toString() {
        return toString("");
    }

    public String toString(String quote) {
        return quote + key + quote + " " + (sort == ASCENDING ? "ASC" : "DESC");
    }
}
