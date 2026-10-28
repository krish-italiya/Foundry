interface EmptyStateProps {
  icon?: string;
  title: string;
  description: string;
  actionLabel?: string;
  onAction?: () => void;
}

export default function EmptyState({
  icon = "◇",
  title,
  description,
  actionLabel,
  onAction
}: EmptyStateProps) {
  return (
    <div className="empty-page-card surface-card">
      <div className="empty-illustration"><span>{icon}</span><i /><b /></div>
      <h2>{title}</h2>
      <p>{description}</p>
      {actionLabel && onAction && (
        <button className="button button-primary" onClick={onAction}>{actionLabel} <span>→</span></button>
      )}
    </div>
  );
}