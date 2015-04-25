/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.protocol.raft;

import net.kuujo.copycat.cluster.Member;
import net.kuujo.copycat.protocol.raft.rpc.*;
import net.kuujo.copycat.protocol.raft.storage.RaftEntry;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Abstract active state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
abstract class ActiveState extends PassiveState {
  protected boolean transition;

  protected ActiveState(RaftProtocol context) {
    super(context);
  }

  /**
   * Transitions to a new state.
   */
  protected CompletableFuture<RaftState.Type> transition(RaftState.Type state) {
    return context.transition(state);
  }

  @Override
  protected CompletableFuture<AppendResponse> append(final AppendRequest request) {
    context.checkThread();
    CompletableFuture<AppendResponse> future = CompletableFuture.completedFuture(logResponse(handleAppend(logRequest(request))));
    // If a transition is required then transition back to the follower state.
    // If the node is already a follower then the transition will be ignored.
    if (transition) {
      transition(RaftState.Type.FOLLOWER);
      transition = false;
    }
    return future;
  }

  /**
   * Starts the append process.
   */
  private AppendResponse handleAppend(AppendRequest request) {
    // If the request indicates a term that is greater than the current term then
    // assign that term and leader to the current context and step down as leader.
    if (request.term() > context.getTerm() || (request.term() == context.getTerm() && context.getLeader() == 0)) {
      context.setTerm(request.term());
      context.setLeader(request.leader());
      transition = true;
    }

    // If the request term is less than the current term then immediately
    // reply false and return our current term. The leader will receive
    // the updated term and step down.
    if (request.term() < context.getTerm()) {
      LOGGER.warn("{} - Rejected {}: request term is less than the current term ({})", context.getCluster().member().id(), request, context.getTerm());
      return AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.log().lastIndex())
        .build();
    } else if (request.logIndex() != 0 && request.logTerm() != 0) {
      return doCheckPreviousEntry(request);
    } else {
      return doAppendEntries(request);
    }
  }

  /**
   * Checks the previous log entry for consistency.
   */
  private AppendResponse doCheckPreviousEntry(AppendRequest request) {
    if (request.logIndex() != 0 && context.log().isEmpty()) {
      LOGGER.warn("{} - Rejected {}: Previous index ({}) is greater than the local log's last index ({})", context.getCluster().member().id(), request, request.logIndex(), context.log().lastIndex());
      return AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.log().lastIndex())
        .build();
    } else if (request.logIndex() != 0 && context.log().lastIndex() != 0 && request.logIndex() > context.log().lastIndex()) {
      LOGGER.warn("{} - Rejected {}: Previous index ({}) is greater than the local log's last index ({})", context.getCluster().member().id(), request, request.logIndex(), context.log().lastIndex());
      return AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.log().lastIndex())
        .build();
    }

    // If the previous entry term doesn't match the local previous term then reject the request.
    RaftEntry entry = context.log().getEntry(request.logIndex());
    if (entry.readTerm() != request.logTerm()) {
      LOGGER.warn("{} - Rejected {}: Request log term does not match local log term {} for the same entry", context.getCluster().member().id(), request, entry.readTerm());
      return AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.log().lastIndex())
        .build();
    } else {
      return doAppendEntries(request);
    }
  }

  /**
   * Appends entries to the local log.
   */
  @SuppressWarnings("unchecked")
  private AppendResponse doAppendEntries(AppendRequest request) {
    // If the log contains entries after the request's previous log index
    // then remove those entries to be replaced by the request entries.
    if (!request.entries().isEmpty()) {

      // Iterate through request entries and append them to the log.
      for (RaftEntry entry : request.entries()) {
        // Replicated snapshot entries are *always* immediately logged and applied to the state machine
        // since snapshots are only taken of committed state machine state. This will cause all previous
        // entries to be removed from the log.
        if (context.log().containsIndex(entry.index())) {
          // Compare the term of the received entry with the matching entry in the log.
          RaftEntry match = context.log().getEntry(entry.index());
          if (match != null) {
            if (entry.readTerm() != match.readTerm()) {
              // We found an invalid entry in the log. Remove the invalid entry and append the new entry.
              // If appending to the log fails, apply commits and reply false to the append request.
              LOGGER.warn("{} - Appended entry term does not match local log, removing incorrect entries", context.getCluster().member().id());
              context.log().truncate(entry.index() - 1);
              try (RaftEntry transfer = context.log().createEntry()) {
                assert entry.index() == transfer.index();
                transfer.write(entry);
              }
              LOGGER.debug("{} - Appended {} to log at index {}", context.getCluster().member().id(), entry, entry.index());
            }
          } else {
            context.log().truncate(entry.index() - 1);
            try (RaftEntry transfer = context.log().createEntry()) {
              assert entry.index() == transfer.index();
              transfer.write(entry);
            }
            LOGGER.debug("{} - Appended {} to log at index {}", context.getCluster().member().id(), entry, entry.index());
          }
        } else {
          // If appending to the log fails, apply commits and reply false to the append request.
          try (RaftEntry transfer = context.log().skip(entry.index() - context.log().lastIndex() - 1).createEntry()) {
            assert entry.index() == transfer.index();
            transfer.write(entry);
          }
          LOGGER.debug("{} - Appended {} to log at index {}", context.getCluster().member().id(), entry, entry.index());
        }
      }
    }

    // If we've made it this far, apply commits and send a successful response.
    doApplyCommits(request.commitIndex());
    doRecycle(request.recycleIndex());
    return AppendResponse.builder()
      .withStatus(Response.Status.OK)
      .withTerm(context.getTerm())
      .withSucceeded(true)
      .withLogIndex(context.log().lastIndex())
      .build();
  }

  /**
   * Applies commits to the local state machine.
   */
  private void doApplyCommits(long commitIndex) {
    // If the synced commit index is greater than the local commit index then
    // apply commits to the local state machine.
    // Also, it's possible that one of the previous write applications failed
    // due to asynchronous communication errors, so alternatively check if the
    // local commit index is greater than last applied. If all the state machine
    // commands have not yet been applied then we want to re-attempt to apply them.
    if (commitIndex != 0 && !context.log().isEmpty()) {
      if (context.getCommitIndex() == 0 || commitIndex > context.getCommitIndex() || context.getCommitIndex() > context.getLastApplied()) {
        LOGGER.debug("{} - Applying {} commits", context.getCluster().member().id(), commitIndex - Math.max(context.getLastApplied(), context.log().firstIndex()));

        // Update the local commit index with min(request commit, last log // index)
        long lastIndex = context.log().lastIndex();
        if (lastIndex != 0) {
          context.setCommitIndex(Math.min(Math.max(commitIndex, context.getCommitIndex() != 0 ? context.getCommitIndex() : commitIndex), lastIndex));

          // If the updated commit index indicates that commits remain to be
          // applied to the state machine, iterate entries and apply them.
          if (context.getCommitIndex() > context.getLastApplied()) {
            // Starting after the last applied entry, iterate through new entries
            // and apply them to the state machine up to the commit index.
            for (long i = Math.max(context.getLastApplied(), context.log().firstIndex()); i <= Math.min(context.getCommitIndex(), lastIndex); i++) {
              // Apply the entry to the state machine.
              applyEntry(i);
            }
          }
        }
      }
    }
  }

  /**
   * Applies the given entry.
   */
  protected void applyEntry(long index) {
    if ((context.getLastApplied() == 0 && index == context.log().firstIndex()) || (context.getLastApplied() != 0 && context.getLastApplied() == index - 1)) {
      RaftEntry entry = context.log().getEntry(index);
      if (entry != null) {
        RaftEntry.Type type = entry.readType();
        if (type == RaftEntry.Type.COMMAND || type == RaftEntry.Type.TOMBSTONE) {
          entry.readKey(KEY.clear());
          entry.readEntry(ENTRY.clear());
          try {
            context.commit(KEY.flip(), ENTRY.flip(), RESULT.clear());
          } catch (Exception e) {
            LOGGER.warn("failed to apply command", e);
          } finally {
            context.setLastApplied(index);
          }
        } else {
          context.setLastApplied(index);
        }
      }
    }
  }

  /**
   * Recycles the log up to the given index.
   */
  private void doRecycle(long compactIndex) {
    if (compactIndex > 0) {
      context.log().recycle(compactIndex);
    }
  }

  @Override
  protected CompletableFuture<PollResponse> poll(PollRequest request) {
    context.checkThread();
    return CompletableFuture.completedFuture(logResponse(handlePoll(logRequest(request))));
  }

  /**
   * Handles a poll request.
   */
  protected PollResponse handlePoll(PollRequest request) {
    if (logUpToDate(request.logIndex(), request.logTerm(), request)) {
      return PollResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withAccepted(true)
        .build();
    } else {
      return PollResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withAccepted(false)
        .build();
    }
  }

  @Override
  protected CompletableFuture<VoteResponse> vote(VoteRequest request) {
    context.checkThread();
    return CompletableFuture.completedFuture(logResponse(handleVote(logRequest(request))));
  }

  /**
   * Handles a vote request.
   */
  protected VoteResponse handleVote(VoteRequest request) {
    // If the request indicates a term that is greater than the current term then
    // assign that term and leader to the current context and step down as leader.
    if (request.term() > context.getTerm()) {
      context.setTerm(request.term());
    }

    // If the request term is not as great as the current context term then don't
    // vote for the candidate. We want to vote for candidates that are at least
    // as up to date as us.
    if (request.term() < context.getTerm()) {
      LOGGER.debug("{} - Rejected {}: candidate's term is less than the current term", context.getCluster().member().id(), request);
      return VoteResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withVoted(false)
        .build();
    }
    // If the requesting candidate is our self then always vote for our self. Votes
    // for self are done by calling the local node. Note that this obviously
    // doesn't make sense for a leader.
    else if (request.candidate() == context.getCluster().member().id()) {
      context.setLastVotedFor(context.getCluster().member().id());
      LOGGER.debug("{} - Accepted {}: candidate is the local member", context.getCluster().member().id(), request);
      return VoteResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withVoted(true)
        .build();
    }
    // If the requesting candidate is not a known member of the cluster (to this
    // node) then don't vote for it. Only vote for candidates that we know about.
    else if (!context.getCluster().members().stream().map(Member::id).collect(Collectors.toSet()).contains(request.candidate())) {
      LOGGER.debug("{} - Rejected {}: candidate is not known to the local member", context.getCluster().member().id(), request);
      return VoteResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withVoted(false)
        .build();
    }
    // If we've already voted for someone else then don't vote again.
    else if (context.getLastVotedFor() == 0 || context.getLastVotedFor() == request.candidate()) {
      if (logUpToDate(request.logIndex(), request.logTerm(), request)) {
        context.setLastVotedFor(request.candidate());
        return VoteResponse.builder()
          .withStatus(Response.Status.OK)
          .withTerm(context.getTerm())
          .withVoted(true)
          .build();
      } else {
        return VoteResponse.builder()
          .withStatus(Response.Status.OK)
          .withTerm(context.getTerm())
          .withVoted(false)
          .build();
      }
    }
    // In this case, we've already voted for someone else.
    else {
      LOGGER.debug("{} - Rejected {}: already voted for {}", context.getCluster().member().id(), request, context.getLastVotedFor());
      return VoteResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withVoted(false)
        .build();
    }
  }

  /**
   * Returns a boolean value indicating whether the given candidate's log is up-to-date.
   */
  private boolean logUpToDate(long index, long term, Request request) {
    // If the log is empty then vote for the candidate.
    if (context.log().isEmpty()) {
      LOGGER.debug("{} - Accepted {}: candidate's log is up-to-date", context.getCluster().member().id(), request);
      return true;
    } else {
      // Otherwise, load the last entry in the log. The last entry should be
      // at least as up to date as the candidates entry and term.
      if (!context.log().isEmpty()) {
        long lastIndex = context.log().lastIndex();
        RaftEntry entry = context.log().getEntry(lastIndex);
        if (entry == null) {
          LOGGER.debug("{} - Accepted {}: candidate's log is up-to-date", context.getCluster().member().id(), request);
          return true;
        }

        if (index != 0 && index >= lastIndex) {
          if (term >= entry.readTerm()) {
            LOGGER.debug("{} - Accepted {}: candidate's log is up-to-date", context.getCluster().member().id(), request);
            return true;
          } else {
            LOGGER.debug("{} - Rejected {}: candidate's last log term ({}) is in conflict with local log ({})", context.getCluster().member().id(), request, term, entry.readTerm());
            return false;
          }
        } else {
          LOGGER.debug("{} - Rejected {}: candidate's last log entry ({}) is at a lower index than the local log ({})", context.getCluster().member().id(), request, index, lastIndex);
          return false;
        }
      } else {
        LOGGER.debug("{} - Accepted {}: candidate's log is up-to-date", context.getCluster().member().id(), request);
        return true;
      }
    }
  }

}
