import { db } from "./initialize-firestore.js";

// Only run on pages that actually have the book list container
const bookList = document.querySelector("#bookList");
if (!bookList) {
  console.warn("bookList element not found; skipping Firestore book list.");
} else {
  databaseGetBook();
}

function databaseGetBook() {
  db.collection("books")
    .get()
    .then((querySnapshot) => {
      querySnapshot.forEach((doc) => {
        loadBooks(doc.data(), doc.id);
      });
    })
    .catch((error) => {
      console.log("Error getting documents: ", error);
    });
}

function loadBooks(book, bookId) {
  // Defensive: some docs might not have createdDate
  const date = book?.createdDate?.toDate ? book.createdDate.toDate() : new Date();
  const day = date.getDate();
  const month = date.getMonth() + 1; // 1-12
  const year = date.getFullYear();

  // Defensive: image path could be missing
  const imgPath = book?.image?.path ? `/assets/${book.image.path}` : "";

  const html = `
    <div class="col-md-4">
      <div class="card mb-4 shadow-sm">
        ${imgPath ? `<img class="bd-placeholder-img" width="100%" height="225" src="${imgPath}" alt="">` : ""}
        <div class="card-body">
          <p class="card-text"><b>${escapeHtml(book?.buchName ?? "")}</b></p>
          <p class="card-text">Author: ${escapeHtml(book?.author ?? "")}<br></p>
          <div class="d-flex justify-content-between align-items-center">
            <div class="btn-group">
              <button type="button"
                      class="btn btn-sm btn-outline-secondary"
                      id="view-${escapeHtmlAttr(bookId)}">View</button>
            </div>
            <div><i class="fa-solid bi bi-star fa-fw"></i>${escapeHtml(String(book?.buchBewertung ?? ""))}</div>
            <small>${day}.${month}.${year}</small>
          </div>
        </div>
      </div>
    </div>
  `;

  bookList.insertAdjacentHTML("beforeend", html);

  // Use CSS.escape for safety (Firestore IDs can contain special chars)
  const btn = document.querySelector(`#${CSS.escape(`view-${bookId}`)}`);
  if (!btn) return;

  btn.addEventListener("click", () => {
    handleViewButtonClickEvent(bookId);
  });
}

function handleViewButtonClickEvent(bookId) {
  // Adjust this path if your book details page lives elsewhere
  location.href = `/books/bookDetails.html?id=${encodeURIComponent(bookId)}`;
  console.log("Routed to: " + bookId);
}

// Simple HTML escaping helpers to avoid breaking markup if fields contain < > etc.
function escapeHtml(str) {
  return String(str)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function escapeHtmlAttr(str) {
  // same as escapeHtml, but kept separate for clarity
  return escapeHtml(str);
}