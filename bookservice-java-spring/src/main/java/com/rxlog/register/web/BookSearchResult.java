package com.rxlog.register.web;

import java.util.List;

public class BookSearchResult {
  private String id;
  private String author;
  private String publisher;
  private Integer pages;
  private String readingStatus;
  private Boolean topBook;

  // optional classification
  private Boolean isFiction; // true=Fiction, false=Non-Fiction, null=unspecified
  private String genre;
  private String subGenre;
  private String themes;

  private Integer width; // mm
  private Integer height; // mm

  private List<String> barcodes;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getAuthor() {
    return author;
  }

  public void setAuthor(String author) {
    this.author = author;
  }

  public String getPublisher() {
    return publisher;
  }

  public void setPublisher(String publisher) {
    this.publisher = publisher;
  }

  public Integer getPages() {
    return pages;
  }

  public void setPages(Integer pages) {
    this.pages = pages;
  }

  public String getReadingStatus() {
    return readingStatus;
  }

  public void setReadingStatus(String readingStatus) {
    this.readingStatus = readingStatus;
  }

  public Boolean getTopBook() {
    return topBook;
  }

  public void setTopBook(Boolean topBook) {
    this.topBook = topBook;
  }

  public Boolean getIsFiction() {
    return isFiction;
  }

  public void setIsFiction(Boolean isFiction) {
    this.isFiction = isFiction;
  }

  public String getGenre() {
    return genre;
  }

  public void setGenre(String genre) {
    this.genre = genre;
  }

  public String getSubGenre() {
    return subGenre;
  }

  public void setSubGenre(String subGenre) {
    this.subGenre = subGenre;
  }

  public String getThemes() {
    return themes;
  }

  public void setThemes(String themes) {
    this.themes = themes;
  }

  public Integer getWidth() {
    return width;
  }

  public void setWidth(Integer width) {
    this.width = width;
  }

  public Integer getHeight() {
    return height;
  }

  public void setHeight(Integer height) {
    this.height = height;
  }

  public List<String> getBarcodes() {
    return barcodes;
  }

  public void setBarcodes(List<String> barcodes) {
    this.barcodes = barcodes;
  }
}
