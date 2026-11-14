package wingmanpvp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PvpRules {
   public static final int MAX_CHARGES = 3;
   public static final float CHARGE_EVERY = 60.0F;
   public static final float PAUSE_MAX = 60.0F;
   public static final float COUNTDOWN = 3.0F;
   public static final int NOBODY = -1;
   public static final int DROP = 2;
   private final float[] charges = new float[]{1.0F, 1.0F};
   private int pausedBy = -1;
   private float pausedFor;
   private float countdown;
   private final Set<String> dropped = new LinkedHashSet<>();
   private final boolean[] resumeVote = new boolean[2];
   private final boolean[] captainHere = new boolean[]{true, true};
   private final List<String> events = new ArrayList<>();

   public float charges(int var1) {
      return this.charges[var1];
   }

   public int pausedBy() {
      return this.pausedBy;
   }

   public float countdown() {
      return this.countdown;
   }

   public float pausedFor() {
      return this.pausedFor;
   }

   public Set<String> dropped() {
      return this.dropped;
   }

   public boolean voted(int var1) {
      return this.resumeVote[var1];
   }

   public boolean paused() {
      return this.pausedBy != -1 || this.countdown > 0.0F;
   }

   public List<String> takeEvents() {
      ArrayList var1 = new ArrayList<>(this.events);
      this.events.clear();
      return var1;
   }

   public void setCaptainHere(int var1, boolean var2) {
      this.captainHere[var1] = var2;
   }

   public void tick(float var1, float var2) {
      if (!this.paused()) {
         for (int var3 = 0; var3 < 2; var3++) {
            this.charges[var3] = Math.min(3.0F, this.charges[var3] + var1 / 60.0F);
         }
      } else if (this.countdown > 0.0F) {
         this.countdown -= var2;
         if (this.countdown <= 0.0F) {
            this.countdown = 0.0F;
            this.events.add("Resumed.");
         }
      } else {
         this.pausedFor += var2;
         if ((this.pausedBy == 0 || this.pausedBy == 1) && this.pausedFor >= 60.0F) {
            this.events.add("The pause ran out (" + Math.round(60.0F) + " s).");
            this.startCountdown();
         }
      }
   }

   public boolean captainPause(int var1, String var2) {
      if (this.countdown > 0.0F) {
         return false;
      } else if (this.pausedBy == -1) {
         if (this.charges[var1] < 1.0F) {
            this.events.add(var2 + " has no pause left (next in " + Math.round((1.0F - this.charges[var1]) * 60.0F) + " s).");
            return false;
         } else {
            this.charges[var1]--;
            this.pausedBy = var1;
            this.pausedFor = 0.0F;
            this.events.add(var2 + " paused (" + (int)Math.floor((double)this.charges[var1]) + " left).");
            return true;
         }
      } else if (this.pausedBy == var1) {
         this.events.add(var2 + " resumed.");
         this.startCountdown();
         return true;
      } else {
         return this.pausedBy == 2 ? this.captainResume(var1, var2) : false;
      }
   }

   public boolean captainResume(int var1, String var2) {
      if (this.pausedBy == 2 && !this.resumeVote[var1]) {
         this.resumeVote[var1] = true;
         this.events.add(var2 + " is ready to go on without " + join(this.dropped) + ".");
         boolean var3 = true;

         for (int var4 = 0; var4 < 2; var4++) {
            if (this.captainHere[var4] && !this.resumeVote[var4]) {
               var3 = false;
            }
         }

         if (var3) {
            this.dropped.clear();
            this.startCountdown();
         }

         return true;
      } else {
         return false;
      }
   }

   public void drop(String var1) {
      this.dropped.add(var1);
      this.countdown = 0.0F;
      if (this.pausedBy != 2) {
         this.pausedBy = 2;
         this.pausedFor = 0.0F;
      }

      this.resumeVote[0] = this.resumeVote[1] = false;
      this.events.add(var1 + " disconnected. Paused until they are back and ready, or both captains go on.");
   }

   public boolean back(String var1) {
      if (!this.dropped.remove(var1)) {
         return false;
      } else {
         this.events.add(var1 + " is back.");
         if (this.dropped.isEmpty() && this.pausedBy == 2) {
            this.startCountdown();
         }

         return true;
      }
   }

   private void startCountdown() {
      this.pausedBy = -1;
      this.pausedFor = 0.0F;
      this.resumeVote[0] = this.resumeVote[1] = false;
      this.countdown = 3.0F;
   }

   private static String join(Set<String> var0) {
      StringBuilder var1 = new StringBuilder();

      for (String var3 : var0) {
         var1.append(var1.length() == 0 ? "" : ", ").append(var3);
      }

      return var1.length() == 0 ? "them" : var1.toString();
   }
}
