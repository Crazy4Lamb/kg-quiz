export default function PageToolbar({ filters, actions, children }) {
  return (
    <div className="page-toolbar">
      {filters && <div className="toolbar-filters">{filters}</div>}
      {children}
      {actions && <div className="toolbar-actions">{actions}</div>}
    </div>
  );
}
