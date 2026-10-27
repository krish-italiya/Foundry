interface FeedbackBannersProps {
  error: string;
  notice: string;
  onDismissError: () => void;
  onDismissNotice: () => void;
}

export default function FeedbackBanners({
  error,
  notice,
  onDismissError,
  onDismissNotice
}: FeedbackBannersProps) {
  return (
    <>
      {error && (
        <div className="alert alert-error" role="alert">
          <span className="alert-symbol">!</span>
          <div><strong>Something needs attention</strong><p>{error}</p></div>
          <button className="alert-close" onClick={onDismissError} aria-label="Dismiss error">×</button>
        </div>
      )}
      {notice && (
        <div className="alert alert-success" role="status">
          <span className="alert-symbol">✓</span>
          <p>{notice}</p>
          <button className="alert-close" onClick={onDismissNotice} aria-label="Dismiss message">×</button>
        </div>
      )}
    </>
  );
}