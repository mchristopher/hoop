package sessionstorage

import (
	"time"

	"github.com/runopsio/hoop/gateway/pgrest"
	pgreview "github.com/runopsio/hoop/gateway/pgrest/review"
	pgsession "github.com/runopsio/hoop/gateway/pgrest/session"
	"github.com/runopsio/hoop/gateway/storagev2"
	"github.com/runopsio/hoop/gateway/storagev2/types"
)

const (
	defaultLimit  int = 100
	defaultOffset int = 0
)

func Put(storage *storagev2.Context, session types.Session) error {
	return pgsession.New().Upsert(storage, session)
}

// FindOne doe not enforce fetching the session by its user.
// However, this is somehow protected by obscurity,
// since the user won't know the session id of a distinct user.
func FindOne(ctx *storagev2.Context, sessionID string) (*types.Session, error) {
	sess, err := pgsession.New().FetchOne(ctx, sessionID)
	if err != nil {
		return nil, err
	}
	if sess == nil {
		return nil, nil
	}
	if sess.NonIndexedStream != nil {
		nonIndexedStreams := sess.NonIndexedStream["stream"]
		for _, i := range nonIndexedStreams {
			sess.EventStream = append(sess.EventStream, i)
		}
	}
	return sess, nil
}

func FindReviewBySID(ctx *storagev2.Context, sessionID string) (*types.Review, error) {
	return pgreview.New().FetchOneBySid(ctx, sessionID)
}

func List(ctx *storagev2.Context, opts ...*types.SessionOption) (*types.SessionList, error) {
	var options []*pgrest.SessionOption
	for _, opt := range opts {
		options = append(options, &pgrest.SessionOption{
			OptionKey: pgrest.SessionOptionKey(opt.OptionKey),
			OptionVal: opt.OptionVal,
		})
	}
	sl, err := pgsession.New().FetchAll(ctx, options...)
	if err != nil {
		if err == pgrest.ErrNotFound {
			return nil, nil
		}
		return nil, err
	}
	sessionList := &types.SessionList{
		Total:       sl.Total,
		HasNextPage: sl.HasNextPage,
	}
	for _, s := range sl.Items {
		_, eventSize := s.GetBlobStream()
		sessionList.Items = append(sessionList.Items, types.Session{
			ID:               s.ID,
			OrgID:            s.OrgID,
			Script:           types.SessionScript{"data": ""}, // do not show the script on listing
			Labels:           s.Labels,
			UserEmail:        s.UserEmail,
			UserID:           s.UserID,
			UserName:         s.UserName,
			Type:             s.ConnectionType,
			Connection:       s.Connection,
			Verb:             s.Verb,
			Status:           s.Status,
			DlpCount:         s.GetRedactCount(),
			EventStream:      nil,
			NonIndexedStream: nil,
			EventSize:        eventSize,
			StartSession:     s.GetCreatedAt(),
			EndSession:       s.GetEndedAt(),
		})
	}
	return sessionList, nil
}

func ListAllSessionsID(ctx *storagev2.Context, fromDate time.Time) ([]*types.Session, error) {
	return pgsession.New().FetchAllFromDate(fromDate)
}
