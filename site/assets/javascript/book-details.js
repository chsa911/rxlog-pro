import { db } from "./initialize-firestore.js";

const book = await getBook();

addInformationToSite(book);

function addInformationToSite(book) {
    const bookTitle = document.getElementById("bookTitle");
    const bookAuthor = document.getElementById("bookAuthor");
    const bookImage = document.getElementById("bookImage");
    const bookBeschreibung = document.getElementById("bookBeschreibung");
    const bookKommentar = document.getElementById("bookKommentar");
    const bookBewertung = document.getElementById("bookBewertung");

    bookTitle.innerHTML = book.buchName;
    bookAuthor.innerHTML = book.author;
    bookImage.src = "../../assets/" + book.image.path;
    console.log(bookImage.src);
    bookBeschreibung.innerHTML = book.buchBeschreibung;
    bookKommentar.innerHTML = book.kommentar;
    bookBewertung.innerHTML = `
    Buchbewertung:  ${book.buchBewertung}/10 <i class="fa-solid bi bi-star fa-fw"></i>
    `
}


async function getBook() {
    let url = new URL(window.location.href);
    let bookId = url.searchParams.get("id");
    const docRef = db.collection("books").doc(bookId);
    let book = null;
    const bookDoc = await docRef.get();

    if(bookDoc.exists) {
        book = await bookDoc.data();
    }

    return book;
}

