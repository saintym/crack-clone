import { Routes, Route, Navigate } from 'react-router-dom';
import LoginPage from './pages/LoginPage';
import ScenariosPage from './pages/ScenariosPage';
import StoriesPage from './pages/StoriesPage';
import ChatPage from './pages/ChatPage';
import ScenarioDetailPage from './pages/ScenarioDetailPage';

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const token = localStorage.getItem('crack-token');
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<ProtectedRoute><ScenariosPage /></ProtectedRoute>} />
      <Route path="/stories/:scenarioName" element={<ProtectedRoute><StoriesPage /></ProtectedRoute>} />
      <Route path="/chat/:storyId" element={<ProtectedRoute><ChatPage /></ProtectedRoute>} />
      <Route path="/scenario/:scenarioName" element={<ProtectedRoute><ScenarioDetailPage /></ProtectedRoute>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
