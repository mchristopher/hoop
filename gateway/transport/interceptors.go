package transport

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/getsentry/sentry-go"
	commongrpc "github.com/runopsio/hoop/common/grpc"
	"github.com/runopsio/hoop/common/log"
	pb "github.com/runopsio/hoop/common/proto"
	"github.com/runopsio/hoop/gateway/agent"
	clientkeysstorage "github.com/runopsio/hoop/gateway/storagev2/clientkeys"
	"github.com/runopsio/hoop/gateway/storagev2/types"
	"github.com/runopsio/hoop/gateway/transport/adminapi"
	"github.com/runopsio/hoop/gateway/user"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

type wrappedStream struct {
	grpc.ServerStream

	newCtx    context.Context
	newCtxVal any
}

type gatewayContextKey struct{}

// https://github.com/grpc/grpc-go/issues/4363#issuecomment-840030503
func (w *wrappedStream) Context() context.Context {
	if w.newCtx != nil {
		return w.newCtx
	}
	ctx := w.ServerStream.Context()
	if w.newCtxVal == nil {
		return ctx
	}
	if md, ok := metadata.FromIncomingContext(ctx); ok {
		w.newCtx = metadata.NewIncomingContext(
			context.WithValue(ctx, gatewayContextKey{}, w.newCtxVal), md.Copy())
		return w.newCtx
	}
	return ctx
}

func parseAuthContextInto(ctx context.Context, into any) error {
	if ctx == nil {
		return status.Error(codes.Internal, "authentication context not found (nil)")
	}
	val := ctx.Value(gatewayContextKey{})
	if val == nil {
		return status.Error(codes.Internal, "authentication context not found")
	}
	switch v := val.(type) {
	case *types.APIContext:
		*into.(*types.APIContext) = *v
	case *types.ClientKey:
		*into.(*types.ClientKey) = *v
	case *agent.Agent:
		*into.(*agent.Agent) = *v
	default:
		return status.Error(codes.Unauthenticated,
			fmt.Sprintf("invalid authentication, missing auth context, type: %T", val))
	}
	return nil
}

func (s *Server) AuthGrpcInterceptor(srv any, ss grpc.ServerStream, info *grpc.StreamServerInfo, handler grpc.StreamHandler) error {
	log.Infof("auth grpc middleware: %v", info.FullMethod)

	md, ok := metadata.FromIncomingContext(ss.Context())
	if !ok {
		return status.Error(codes.InvalidArgument, "missing context metadata")
	}
	clientOrigin := md.Get("origin")
	if len(clientOrigin) == 0 {
		md.Delete("authorization")
		log.Debugf("client missing origin, client-metadata=%v", md)
		return status.Error(codes.InvalidArgument, "missing client origin")
	}

	isAgentOrigin := clientOrigin[0] == pb.ConnectionOriginAgent
	bearerToken, err := parseBearerToken(s.Profile, isAgentOrigin, md)
	if err != nil {
		return err
	}

	var ctxVal any
	switch {
	// administrative api authentication
	case strings.HasPrefix(bearerToken, adminapi.PrefixAuthKey):
		if !adminapi.Authenticate(bearerToken) {
			log.Errorf("invalid admin api authentication, tokenlen=%v", len(bearerToken))
			return status.Errorf(codes.Unauthenticated, "failed to authenticate internal request")
		}
		// decode the user information from the header
		uctx, err := getUserInfo(md)
		if err != nil {
			return err
		}
		log.With(
			"org", uctx.OrgID, "orgname", uctx.OrgName,
			"userid", uctx.UserID, "email", uctx.UserEmail,
			"usergrps", uctx.UserGroups, "name", uctx.UserName,
			"slackid", uctx.SlackID, "status", uctx.UserStatus,
		).Infof("admin api - decoded userinfo")
		ctxVal = uctx
	// agent key authentication
	case strings.HasPrefix(bearerToken, "x-agt-"):
		ag, err := s.AgentService.FindByToken(bearerToken)
		if err != nil || ag == nil {
			md.Delete("authorization")
			log.Debugf("invalid agent authentication, tokenlength=%v, client-metadata=%v", len(bearerToken), md)
			return status.Errorf(codes.Unauthenticated, "invalid authentication")
		}
		ctxVal = ag
	// agent client keys (dsn) authentication
	// keep compatibility with old clients
	// hoopagent/sdk or hoopagent/sidecar
	case strings.HasPrefix(mdget(md, "user-agent"), "hoopagent/s"):
		clientKey, err := clientkeysstorage.ValidateDSN(s.StoreV2, bearerToken)
		if err != nil {
			log.Error("failed validating dsn authentication, err=%v", err)
			sentry.CaptureException(err)
			return status.Errorf(codes.Internal, "failed validating dsn")
		}
		if clientKey == nil {
			md.Delete("authorization")
			log.Debugf("invalid agent authentication, tokenlength=%v, client-metadata=%v", len(bearerToken), md)
			return status.Errorf(codes.Unauthenticated, "invalid authentication")
		}
		ctxVal = clientKey
	// client proxy authentication (access token)
	default:
		sub, err := s.exchangeUserToken(bearerToken)
		if err != nil {
			log.Debugf("failed verifying access token, reason=%v", err)
			return status.Errorf(codes.Unauthenticated, "invalid authentication")
		}
		userCtx, err := s.UserService.FindBySub(sub)
		if err != nil || userCtx.User == nil {
			return status.Errorf(codes.Unauthenticated, "invalid authentication")
		}
		ctxVal = userCtx.ToAPIContext()
	}

	return handler(srv, &wrappedStream{ss, nil, ctxVal})
}

func getUserInfo(md metadata.MD) (*types.APIContext, error) {
	encUserInfo := md.Get(string(commongrpc.OptionUserInfo))
	if len(encUserInfo) == 0 {
		return nil, status.Errorf(codes.Unauthenticated, "invalid authentication, missing system attributes")
	}
	userInfoJson, err := base64.StdEncoding.DecodeString(encUserInfo[0])
	if err != nil {
		log.Errorf("failed decoding (base64) user info: %v", err)
		return nil, status.Errorf(codes.Unauthenticated, "invalid authentication, failed decoding (base64) user info")
	}
	var usrctx types.APIContext
	if err := json.Unmarshal(userInfoJson, &usrctx); err != nil {
		log.Errorf("failed decoding (json) user info: %v", err)
		return nil, status.Errorf(codes.Unauthenticated, "invalid authentication, failed decoding (json) user info")
	}
	return &usrctx, nil
}

func parseBearerToken(environment string, isAgentOrigin bool, md metadata.MD) (string, error) {
	if environment == pb.DevProfile {
		bearerToken := "x-hooper-test-token"
		if isAgentOrigin {
			bearerToken = "x-agt-test-token"
		}
		return bearerToken, nil
	}
	t := md.Get("authorization")
	if len(t) == 0 {
		log.Debugf("missing authorization header, client-metadata=%v", md)
		return "", status.Error(codes.Unauthenticated, "invalid authentication")
	}

	tokenValue := t[0]
	tokenParts := strings.Split(tokenValue, " ")
	if len(tokenParts) != 2 || tokenParts[0] != "Bearer" || tokenParts[1] == "" {
		log.Debugf("authorization header in wrong format, client-metadata=%v", md)
		return "", status.Error(codes.Unauthenticated, "invalid authentication")
	}

	return tokenParts[1], nil
}

func parseToLegacyUserContext(apictx *types.APIContext) *user.Context {
	return &user.Context{
		Org: &user.Org{
			Id:   apictx.OrgID,
			Name: apictx.OrgName,
		},
		User: &user.User{
			Id:      apictx.UserID,
			Org:     apictx.OrgID,
			Name:    apictx.UserName,
			Email:   apictx.UserEmail,
			Status:  user.StatusType(apictx.UserStatus),
			SlackID: apictx.SlackID, // TODO: check this
			Groups:  apictx.UserGroups,
		},
	}
}
