import React from 'react';
import ReactDOM from 'react-dom/client';
import { ThemeProvider, applyTheme, readThemeId } from './theme.jsx';
import App from './App.jsx';
import '@xyflow/react/dist/style.css';
import './index.css';

applyTheme(readThemeId());

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ThemeProvider>
      <App />
    </ThemeProvider>
  </React.StrictMode>
);
