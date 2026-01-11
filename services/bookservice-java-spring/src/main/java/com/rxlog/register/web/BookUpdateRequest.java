package com.rxlog.register.web;

import java.util.List;

public class BookUpdateRequest {
  private Integer pages;
  private String readingStatus; // in_progress | finished | abandoned
  private Boolean topBook;
  private Integer width; // mm
  private Integer height; // mm

  // optional classification
  private Boolean isFiction; // true=Fiction, false=Non-Fiction, null=unspecified
  private boolean isFictionPresent;
  private String genre;
  private boolean genrePresent;
  private String subGenre;
  private boolean subGenrePresent;
  private String themes;
  private boolean themesPresent;
  private List<String> barcodes;

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

  public Boolean getIsFiction() {
    return isFiction;
  }

  public boolean isIsFictionPresent() {
    return isFictionPresent;
  }

  public void setIsFiction(Boolean isFiction) {
    this.isFiction = isFiction;
    this.isFictionPresent = true;
  }

  public String getGenre() {
    return genre;
  }

  public boolean isGenrePresent() {
    return genrePresent;
  }

  public void setGenre(String genre) {
    this.genre = genre;
    this.genrePresent = true;
  }

  public String getSubGenre() {
    return subGenre;
  }

  public boolean isSubGenrePresent() {
    return subGenrePresent;
  }

  public void setSubGenre(String subGenre) {
    this.subGenre = subGenre;
    this.subGenrePresent = true;
  }

  public String getThemes() {
    return themes;
  }

  public boolean isThemesPresent() {
    return themesPresent;
  }

  public void setThemes(String themes) {
    this.themes = themes;
    this.themesPresent = true;
  }

  public List<String> getBarcodes() {
    return barcodes;
  }

  public void setBarcodes(List<String> barcodes) {
    this.barcodes = barcodes;
  }
}
