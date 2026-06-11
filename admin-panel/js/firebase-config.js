const firebaseConfig = {
  apiKey: "AIzaSyA2KthBTD00xFbwVxvYhJgPyJ-GPLhQmyA",
  authDomain: "kava-signage-2026.firebaseapp.com",
  projectId: "kava-signage-2026",
  storageBucket: "kava-signage-2026.firebasestorage.app",
  messagingSenderId: "99606044465",
  appId: "1:99606044465:web:34458e3f74fc50a3221a10"
};

firebase.initializeApp(firebaseConfig);
window.firebaseApp = firebase.app();
window.firebaseDb = firebase.firestore();
window.firebaseStorage = firebase.storage();
console.log('[Teliki] Firebase initialized');
