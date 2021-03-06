package xyz.nucleoid.bedwars.game.active;

import xyz.nucleoid.plasmid.game.player.GameTeam;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class BwWinStateLogic {
    private final BwActive game;

    BwWinStateLogic(BwActive game) {
        this.game = game;
    }

    @Nullable
    public WinResult checkWinResult() {
        this.checkEliminatedTeams();

        // if there's only one team, disable the win state
        if (this.game.getTeamCount() <= 1) {
            return null;
        }

        List<BwActive.TeamState> remainingTeams = this.game.teams()
                .filter(team -> !team.eliminated)
                .collect(Collectors.toList());

        if (remainingTeams.size() <= 1) {
            if (remainingTeams.size() == 1) {
                BwActive.TeamState winningTeam = remainingTeams.get(0);
                return WinResult.team(winningTeam.team);
            } else {
                return WinResult.draw();
            }
        }

        return null;
    }

    private void checkEliminatedTeams() {
        Stream<BwActive.TeamState> eliminatedTeams = this.game.teams()
                .filter(team -> !team.eliminated)
                .filter(team -> {
                    long remainingCount = this.countRemainingPlayers(team.team);
                    return remainingCount <= 0;
                });

        eliminatedTeams.forEach(this::eliminateTeam);
    }

    public void eliminatePlayer(BwParticipant participant) {
        participant.eliminated = true;

        BwActive.TeamState teamState = this.game.getTeam(participant.team);
        if (teamState != null && !teamState.eliminated) {
            long remainingCount = this.countRemainingPlayers(participant.team);
            if (remainingCount <= 0) {
                this.eliminateTeam(teamState);
            }
        }
    }

    private long countRemainingPlayers(GameTeam team) {
        return this.game.participantsFor(team)
                .filter(BwParticipant::isAlive)
                .count();
    }

    private void eliminateTeam(BwActive.TeamState teamState) {
        teamState.eliminated = true;

        this.game.participantsFor(teamState.team).forEach(participant -> {
            participant.eliminated = true;
        });

        this.game.broadcast.broadcastTeamEliminated(teamState.team);
    }

    public static class WinResult {
        private final GameTeam team;

        private WinResult(GameTeam team) {
            this.team = team;
        }

        public static WinResult team(GameTeam team) {
            return new WinResult(team);
        }

        public static WinResult draw() {
            return new WinResult(null);
        }

        @Nullable
        public GameTeam getTeam() {
            return this.team;
        }

        public boolean isDraw() {
            return this.team == null;
        }
    }
}
