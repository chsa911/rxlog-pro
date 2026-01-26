const firebaseConfig = {
    apiKey: "AIzaSyBPCkB7IY-MmoY7oEc0Ohz6WPSrR-t9nEc",
    authDomain: "bookstore-519db.firebaseapp.com",
    projectId: "bookstore-519db",
    storageBucket: "bookstore-519db.appspot.com",
    messagingSenderId: "700323702151",
    appId: "1:700323702151:web:d27fe40c5f5a41265cdf22",
    measurementId: "G-SRDS939BK5",
  };
  // Initialize Firebase
  const app = firebase.initializeApp(firebaseConfig);
  const analytics = firebase.analytics(app);
  const db = firebase.firestore(app);

  export {
    app, analytics, db
  }