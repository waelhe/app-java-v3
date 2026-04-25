'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import {
  Users, Heart, Calendar, MapPin, Star, ChevronLeft, ChevronRight,
  HandHeart, Gift, MessageCircle, Eye, Clock, ArrowRight, Search,
  Filter, Plus, ThumbsUp, Share2, Bookmark, Flame, Sparkles, Trophy,
  GraduationCap, Briefcase, Building, Phone, Mail, Globe, Send,
  Image, Smile, Hash, Pin, Lock, Award, TrendingUp
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';

// ═══════════════════════════════════════════════════════════════
// بيانات المجتمع
// ═══════════════════════════════════════════════════════════════

// أقسام المنتدى
const forumCategories = [
  { id: 'fc1', name: 'نقاشات عامة', nameEn: 'General Discussion', description: 'نقاشات متنوعة حول مختلف المواضيع', descriptionEn: 'Various discussions on different topics', icon: MessageCircle, color: 'bg-blue-500', topics: 234, posts: 1234 },
  { id: 'fc2', name: 'أخبار الحي', nameEn: 'Neighborhood News', description: 'آخر أخبار وأحداث المنطقة', descriptionEn: 'Latest news and events in the area', icon: Flame, color: 'bg-orange-500', topics: 156, posts: 890 },
  { id: 'fc3', name: 'أسئلة وأجوبة', nameEn: 'Q&A', description: 'اسأل وسنجيبك', descriptionEn: 'Ask and we will answer', icon: Sparkles, color: 'bg-violet-500', topics: 89, posts: 456 },
  { id: 'fc4', name: 'توصيات ومراجعات', nameEn: 'Reviews & Recommendations', description: 'شارك تجاربك وتوصياتك', descriptionEn: 'Share your experiences and recommendations', icon: Star, color: 'bg-amber-500', topics: 67, posts: 345 },
  { id: 'fc5', name: 'خدمات ومساعدة', nameEn: 'Services & Help', description: 'اطلب المساعدة أو قدم خدمة', descriptionEn: 'Request help or offer a service', icon: HandHeart, color: 'bg-rose-500', topics: 123, posts: 567 },
  { id: 'fc6', name: 'مبادلات ومشاريع', nameEn: 'Exchanges & Projects', description: 'مشاريع جماعية ومبادلات', descriptionEn: 'Group projects and exchanges', icon: Gift, color: 'bg-emerald-500', topics: 45, posts: 234 },
];

// مواضيع المنتدى
const forumTopics = [
  { id: 'ft1', title: 'أفضل المطاعم في المنطقة؟', titleEn: 'Best restaurants in the area?', author: 'أحمد محمد', authorEn: 'Ahmed Mohamed', category: 'توصيات', categoryEn: 'Reviews', replies: 23, views: 156, lastReply: 'منذ ساعة', pinned: true, hot: true },
  { id: 'ft2', title: 'متى سيتم إصلاح طريق الساحة؟', titleEn: 'When will the square road be fixed?', author: 'سارة علي', authorEn: 'Sara Ali', category: 'أخبار', categoryEn: 'News', replies: 45, views: 289, lastReply: 'منذ 30 دقيقة', hot: true },
  { id: 'ft3', title: 'مطلوب معلم خصوصي للرياضيات', titleEn: 'Looking for math tutor', author: 'محمد خالد', authorEn: 'Mohamed Khaled', category: 'خدمات', categoryEn: 'Services', replies: 12, views: 89, lastReply: 'منذ 3 ساعات' },
  { id: 'ft4', title: 'حفل زفاف جماعي للشهر القادم', titleEn: 'Group wedding next month', author: 'فريق التطوع', authorEn: 'Volunteer Team', category: 'مشاريع', categoryEn: 'Projects', replies: 67, views: 456, lastReply: 'منذ 5 ساعات', pinned: true },
  { id: 'ft5', title: 'نصائح لترشيد استهلاك الكهرباء', titleEn: 'Tips for saving electricity', author: 'نور الدين', authorEn: 'Nour El-Din', category: 'نقاشات', categoryEn: 'Discussion', replies: 34, views: 178, lastReply: 'منذ يوم' },
  { id: 'ft6', title: 'مباراة كرة القدم الأسبوعية', titleEn: 'Weekly football match', author: 'نادي الرياضة', authorEn: 'Sports Club', category: 'أنشطة', categoryEn: 'Activities', replies: 28, views: 134, lastReply: 'منذ ساعتين' },
];

// ردود المنتدى
const forumReplies = [
  { id: 'fr1', author: 'أحمد', authorEn: 'Ahmed', content: 'أفضل مطعم هو مطعم الشام، جربته شخصياً ورائع!', contentEn: 'Best restaurant is Al-Sham, I tried it personally and it is great!', time: 'منذ ساعة', likes: 12 },
  { id: 'fr2', author: 'محمد', authorEn: 'Mohamed', content: 'أنصح بمطعم البيك للمشاوي، أسعار معقولة وجودة عالية', contentEn: 'I recommend Al-Baik for grills, reasonable prices and high quality', time: 'منذ ساعتين', likes: 8 },
];

// المؤسسات الخيرية
const charities = [
  { id: 'ch1', name: 'جمعية البر الخيرية', nameEn: 'Al-Birr Charity', type: 'خيري', typeEn: 'Charity', description: 'مساعدة الأسر المحتاجة', descriptionEn: 'Helping needy families', image: 'https://images.unsplash.com/photo-1593113598332-cd288d649433?auto=format&fit=crop&w=400&q=80', location: 'الساحة', phone: '011-2345678', volunteers: 250, rating: 4.9, verified: true },
  { id: 'ch2', name: 'بنك الطعام', nameEn: 'Food Bank', type: 'إنساني', typeEn: 'Humanitarian', description: 'توزيع الطعام للمحتاجين', descriptionEn: 'Food distribution for needy', image: 'https://images.unsplash.com/photo-1488521787991-ed7bbaae773c?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', phone: '011-2345679', volunteers: 120, rating: 4.8, verified: true },
  { id: 'ch3', name: 'جمعية رعاية الأيتام', nameEn: 'Orphan Care', type: 'خيري', typeEn: 'Charity', description: 'كفالة ورعاية الأيتام', descriptionEn: 'Sponsorship and care for orphans', image: 'https://images.unsplash.com/photo-1488521787991-ed7bbaae773c?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي', phone: '011-2345680', volunteers: 85, rating: 5.0, verified: true },
  { id: 'ch4', name: 'جمعية رعاية المسنين', nameEn: 'Elderly Care', type: 'اجتماعي', typeEn: 'Social', description: 'رعاية كبار السن', descriptionEn: 'Care for the elderly', image: 'https://images.unsplash.com/photo-1516307365426-bea591f05011?auto=format&fit=crop&w=400&q=80', location: 'الحي الشرقي', phone: '011-2345681', volunteers: 75, rating: 4.9, verified: true },
];

// الفعاليات
const events = [
  { id: 'e1', title: 'سوق خيري أسبوعي', titleEn: 'Weekly Charity Market', date: 'كل سبت', time: '9 ص - 2 م', location: 'الساحة', locationEn: 'Square', image: 'https://images.unsplash.com/photo-1555529669-e69e7aa0ba9a?auto=format&fit=crop&w=400&q=80', attendees: 150, category: 'خيري', categoryEn: 'Charity' },
  { id: 'e2', title: 'دورة تعليمية مجانية', titleEn: 'Free Educational Course', date: '15 الشهر القادم', time: '4 م - 7 م', location: 'المركز الثقافي', locationEn: 'Cultural Center', image: 'https://images.unsplash.com/photo-1509062522246-3755977927d7?auto=format&fit=crop&w=400&q=80', attendees: 45, category: 'تعليمي', categoryEn: 'Education' },
  { id: 'e3', title: 'مباراة كرة قدم', titleEn: 'Football Match', date: 'كل جمعة', time: '4 م', location: 'ملعب الحي', locationEn: 'Neighborhood Stadium', image: 'https://images.unsplash.com/photo-1574629810360-7efbbe195018?auto=format&fit=crop&w=400&q=80', attendees: 80, category: 'رياضي', categoryEn: 'Sports' },
  { id: 'e4', title: 'حفل زفاف جماعي', titleEn: 'Group Wedding', date: '20 الشهر القادم', time: '6 م', location: 'قاعة الأفراح', locationEn: 'Wedding Hall', image: 'https://images.unsplash.com/photo-1519741497674-611481863552?auto=format&fit=crop&w=400&q=80', attendees: 300, category: 'اجتماعي', categoryEn: 'Social', featured: true },
  { id: 'e5', title: 'معرض فني', titleEn: 'Art Exhibition', date: '10 الشهر القادم', time: '10 ص - 8 م', location: 'المركز الثقافي', locationEn: 'Cultural Center', image: 'https://images.unsplash.com/photo-1531243269054-5ebf6f34081e?auto=format&fit=crop&w=400&q=80', attendees: 120, category: 'ثقافي', categoryEn: 'Cultural' },
  { id: 'e6', title: 'مبادرة تنظيف الحي', titleEn: 'Neighborhood Cleanup', date: 'كل أحد', time: '8 ص - 11 ص', location: 'جميع أنحاء الحي', locationEn: 'All Neighborhood', image: 'https://images.unsplash.com/photo-1532996122724-e3c354a0b15b?auto=format&fit=crop&w=400&q=80', attendees: 50, category: 'تطوعي', categoryEn: 'Volunteering' },
];

// الأخبار المحلية
const localNews = [
  { id: 'n1', title: 'افتتاح مركز طبي جديد', titleEn: 'New Medical Center Opening', summary: 'تم افتتاح مركز طبي جديد في الحي الغربي يقدم خدمات متعددة', summaryEn: 'A new medical center opened in the West District offering various services', image: 'https://images.unsplash.com/photo-1519494026892-80bbd2d6fd0d?auto=format&fit=crop&w=400&q=80', date: 'اليوم', category: 'صحة', categoryEn: 'Health', views: 456 },
  { id: 'n2', title: 'إصلاح طريق الساحة الرئيسي', titleEn: 'Main Square Road Repair', summary: 'بدأت أعمال صيانة طريق الساحة الرئيسي وستستمر لمدة أسبوع', summaryEn: 'Maintenance work started on the main square road and will continue for a week', image: 'https://images.unsplash.com/photo-1515162816999-a0c47dc192f7?auto=format&fit=crop&w=400&q=80', date: 'أمس', category: 'بنية تحتية', categoryEn: 'Infrastructure', views: 789 },
  { id: 'n3', title: 'نجاح حملة التبرع بالدم', titleEn: 'Blood Donation Campaign Success', summary: 'تجاوز عدد المتبرعين 200 شخص في حملة التبرع بالدم', summaryEn: 'Donors exceeded 200 people in the blood donation campaign', image: 'https://images.unsplash.com/photo-1615461066841-6116e61058f4?auto=format&fit=crop&w=400&q=80', date: 'منذ يومين', category: 'صحة', categoryEn: 'Health', views: 345, featured: true },
  { id: 'n4', title: 'إعلان أوقات الدوام الرمضاني', titleEn: 'Ramadan Working Hours', summary: 'أعلنت البلدية عن أوقات الدوام الجديدة خلال شهر رمضان', summaryEn: 'Municipality announced new working hours during Ramadan', image: 'https://images.unsplash.com/photo-1595079676339-1534801ad6cf?auto=format&fit=crop&w=400&q=80', date: 'منذ 3 أيام', category: 'إعلانات', categoryEn: 'Announcements', views: 567 },
  { id: 'n5', title: 'بطولة الشطرنج المحلية', titleEn: 'Local Chess Championship', summary: 'انطلاق بطولة الشطرنج السنوية بمشاركة 50 لاعب', summaryEn: 'Annual chess championship starts with 50 players participating', image: 'https://images.unsplash.com/photo-1529699211952-734e80c4d42b?auto=format&fit=crop&w=400&q=80', date: 'منذ 4 أيام', category: 'رياضة', categoryEn: 'Sports', views: 234 },
];

// الخدمات المجتمعية
const communityServices = [
  { id: 'cs1', name: 'مركز شباب قدسيا', nameEn: 'Qudsaya Youth Center', type: 'شبابي', typeEn: 'Youth', description: 'أنشطة وبرامج شبابية', descriptionEn: 'Youth activities and programs', image: 'https://images.unsplash.com/photo-1529156069898-49953e39b3ac?auto=format&fit=crop&w=400&q=80', members: 180, rating: 4.7 },
  { id: 'cs2', name: 'نادي رياضي قدسيا', nameEn: 'Qudsaya Sports Club', type: 'رياضي', typeEn: 'Sports', description: 'فريق كرة قدم وأنشطة رياضية', descriptionEn: 'Football team and sports activities', image: 'https://images.unsplash.com/photo-1574629810360-7efbbe195018?auto=format&fit=crop&w=400&q=80', members: 320, rating: 4.6 },
  { id: 'cs3', name: 'مركز التعليم المستمر', nameEn: 'Continuous Learning Center', type: 'تعليمي', typeEn: 'Education', description: 'دورات تعليمية وتدريبية مجانية', descriptionEn: 'Free educational and training courses', image: 'https://images.unsplash.com/photo-1509062522246-3755977927d7?auto=format&fit=crop&w=400&q=80', members: 200, rating: 4.8 },
  { id: 'cs4', name: 'فريق التطوع المجتمعي', nameEn: 'Community Volunteer Team', type: 'تطوعي', typeEn: 'Volunteering', description: 'مبادرات تطوعية أسبوعية', descriptionEn: 'Weekly volunteer initiatives', image: 'https://images.unsplash.com/photo-1559027615-cd4628902d4a?auto=format&fit=crop&w=400&q=80', members: 150, rating: 4.7 },
];

// فرص التطوع
const volunteerOpportunities = [
  { id: 'v1', title: 'توزيع طرود غذائية', titleEn: 'Food Package Distribution', organization: 'جمعية البر', organizationEn: 'Al-Birr Charity', date: 'كل سبت', time: '9 ص - 12 م', location: 'مركز الجمعية', spots: 20, registered: 15 },
  { id: 'v2', title: 'دروس تقوية مجانية', titleEn: 'Free Tutoring', organization: 'مركز التعليم', organizationEn: 'Learning Center', date: 'يومياً', time: '4 م - 6 م', location: 'المركز الثقافي', spots: 10, registered: 8 },
  { id: 'v3', title: 'زيارة دار المسنين', titleEn: 'Elderly Home Visit', organization: 'جمعية الرعاية', organizationEn: 'Care Association', date: 'كل أحد', time: '10 ص - 12 م', location: 'دار المسنين', spots: 15, registered: 12 },
  { id: 'v4', title: 'تنظيف الحي', titleEn: 'Neighborhood Cleanup', organization: 'فريق التطوع', organizationEn: 'Volunteer Team', date: 'كل أحد', time: '8 ص - 11 ص', location: 'جميع أنحاء الحي', spots: 30, registered: 25 },
];

// ═══════════════════════════════════════════════════════════════
// المكونات الفرعية
// ═══════════════════════════════════════════════════════════════

interface CardProps {
  item: {
    id: string;
    name?: string;
    nameEn?: string;
    title?: string;
    titleEn?: string;
    image: string;
    location?: string;
    locationEn?: string;
    rating?: number;
    members?: number;
    date?: string;
    time?: string;
    category?: string;
    categoryEn?: string;
    [key: string]: unknown;
  };
  isArabic: boolean;
  type: 'charity' | 'event' | 'news' | 'service';
}

function Card({ item, isArabic, type }: CardProps) {
  const [isFavorite, setIsFavorite] = useState(false);
  const name = item.name || item.title || '';
  const nameEn = item.nameEn || item.titleEn || '';

  return (
    <div className="flex-shrink-0 w-[200px] sm:w-[240px] bg-white rounded-xl border border-gray-100 overflow-hidden hover:shadow-lg transition-all cursor-pointer group">
      {/* Image */}
      <div className="relative aspect-[4/3] overflow-hidden">
        <img src={item.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
        <div className="absolute inset-0 bg-gradient-to-t from-black/40 to-transparent" />

        {/* Favorite */}
        <button
          onClick={(e) => { e.stopPropagation(); setIsFavorite(!isFavorite); }}
          className="absolute top-2 right-2 p-1.5 bg-white/80 rounded-full hover:bg-white transition-colors"
        >
          <Heart className={`w-4 h-4 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-gray-600'}`} />
        </button>

        {/* Category Badge */}
        {item.category && (
          <span className="absolute top-2 left-2 px-2 py-0.5 bg-white/90 text-gray-700 text-[10px] font-bold rounded-full">
            {isArabic ? item.category : item.categoryEn}
          </span>
        )}

        {/* Date Badge */}
        {item.date && (
          <div className="absolute bottom-2 left-2 px-2 py-1 bg-white/90 rounded-lg">
            <div className="flex items-center gap-1 text-xs font-bold text-gray-700">
              <Calendar className="w-3 h-3" />
              {item.date}
            </div>
          </div>
        )}
      </div>

      {/* Info */}
      <div className="p-3">
        <h3 className="font-bold text-gray-900 text-sm mb-1 line-clamp-1">
          {isArabic ? name : nameEn}
        </h3>

        {item.rating && (
          <div className="flex items-center gap-1 mb-1">
            <Star className="w-3.5 h-3.5 text-amber-400 fill-amber-400" />
            <span className="text-xs font-bold">{item.rating}</span>
          </div>
        )}

        {item.members && (
          <div className="flex items-center gap-1 text-xs text-gray-500 mb-1">
            <Users className="w-3 h-3" />
            <span>{item.members} {isArabic ? 'عضو' : 'members'}</span>
          </div>
        )}

        {(item.location || item.locationEn) && (
          <div className="flex items-center gap-1 text-xs text-gray-500">
            <MapPin className="w-3 h-3" />
            <span className="line-clamp-1">{isArabic ? item.location : item.locationEn}</span>
          </div>
        )}

        {item.time && (
          <div className="flex items-center gap-1 text-xs text-gray-500 mt-1">
            <Clock className="w-3 h-3" />
            <span>{item.time}</span>
          </div>
        )}
      </div>
    </div>
  );
}

interface TopicItemProps {
  topic: {
    id: string;
    title: string;
    titleEn: string;
    author: string;
    authorEn: string;
    category: string;
    categoryEn: string;
    replies: number;
    views: number;
    lastReply: string;
    pinned?: boolean;
    hot?: boolean;
  };
  isArabic: boolean;
}

function TopicItem({ topic, isArabic }: TopicItemProps) {
  return (
    <div className="flex items-center gap-3 p-3 bg-white rounded-xl border border-gray-100 hover:border-violet-200 hover:shadow-md transition-all cursor-pointer">
      {/* Avatar */}
      <div className="w-10 h-10 bg-gradient-to-br from-violet-500 to-purple-600 rounded-full flex items-center justify-center text-white font-bold text-sm">
        {topic.author.charAt(0)}
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 mb-0.5">
          {topic.pinned && <Pin className="w-3 h-3 text-amber-500" />}
          {topic.hot && <Flame className="w-3 h-3 text-orange-500" />}
          <h3 className="font-bold text-gray-900 text-sm line-clamp-1">
            {isArabic ? topic.title : topic.titleEn}
          </h3>
        </div>
        <div className="flex items-center gap-3 text-xs text-gray-500">
          <span>{isArabic ? topic.author : topic.authorEn}</span>
          <span className="px-2 py-0.5 bg-gray-100 rounded-full text-[10px]">
            {isArabic ? topic.category : topic.categoryEn}
          </span>
        </div>
      </div>

      {/* Stats */}
      <div className="flex items-center gap-4 text-xs text-gray-500">
        <div className="flex items-center gap-1">
          <MessageCircle className="w-3.5 h-3.5" />
          <span>{topic.replies}</span>
        </div>
        <div className="flex items-center gap-1">
          <Eye className="w-3.5 h-3.5" />
          <span>{topic.views}</span>
        </div>
        <span className="text-[10px]">{topic.lastReply}</span>
      </div>
    </div>
  );
}

interface SectionProps {
  id: string;
  title: string;
  titleEn: string;
  icon: React.ElementType;
  iconColor: string;
  isArabic: boolean;
  children: React.ReactNode;
}

function Section({ id, title, titleEn, icon: Icon, iconColor, isArabic, children }: SectionProps) {
  return (
    <section id={id} className="scroll-mt-32 py-6 border-b border-gray-100 last:border-b-0">
      <div className="flex items-center gap-3 mb-4">
        <div className={`p-2 ${iconColor} rounded-xl`}>
          <Icon className="w-5 h-5 text-white" />
        </div>
        <div className="flex-1">
          <h2 className="text-lg font-black text-gray-900">
            {isArabic ? title : titleEn}
          </h2>
        </div>
      </div>
      {children}
    </section>
  );
}

// ═══════════════════════════════════════════════════════════════
// الصفحة الرئيسية
// ═══════════════════════════════════════════════════════════════

export default function CommunityPage() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const [searchQuery, setSearchQuery] = useState('');
  const [activeTab, setActiveTab] = useState('all');

  const sections = [
    { id: 'forum', name: 'المنتدى', nameEn: 'Forum' },
    { id: 'charities', name: 'المؤسسات الخيرية', nameEn: 'Charities' },
    { id: 'events', name: 'الفعاليات', nameEn: 'Events' },
    { id: 'news', name: 'الأخبار', nameEn: 'News' },
    { id: 'volunteer', name: 'التطوع', nameEn: 'Volunteering' },
  ];

  const scrollToSection = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' });
  };

  const scrollRef = React.useRef<HTMLDivElement>(null);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({
        left: direction === 'left' ? -300 : 300,
        behavior: 'smooth'
      });
    }
  };

  return (
    <main className="min-h-screen bg-gradient-to-b from-purple-50 to-white" dir={isArabic ? 'rtl' : 'ltr'}>
      {/* Header */}
      <header className="sticky top-0 z-50 bg-white/95 backdrop-blur-md border-b border-purple-100 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 py-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Link href="/" className="p-2 hover:bg-gray-100 rounded-xl transition-colors">
                <ArrowRight className="w-5 h-5 text-gray-600" />
              </Link>
              <div className="p-2 bg-gradient-to-br from-purple-500 to-violet-600 rounded-xl shadow-lg">
                <Users className="w-6 h-6 text-white" />
              </div>
              <div>
                <h1 className="text-lg font-black text-gray-900">
                  {isArabic ? 'المجتمع والمنتدى' : 'Community & Forum'}
                </h1>
                <p className="text-xs text-gray-500">
                  {isArabic ? 'تواصل وشارك مع مجتمعك' : 'Connect and share with your community'}
                </p>
              </div>
            </div>
          </div>

          {/* Search */}
          <div className="mt-3 relative">
            <Search className="absolute right-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={isArabic ? 'ابحث في المنتدى...' : 'Search in forum...'}
              className="w-full pr-10 pl-4 py-3 bg-gray-100 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-purple-500"
            />
          </div>
        </div>
      </header>

      {/* Quick Jump */}
      <div className="sticky top-[130px] z-40 bg-white/95 backdrop-blur-md border-b border-gray-100 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 py-2">
          <div className="flex gap-2 overflow-x-auto pb-1 scrollbar-hide">
            {sections.map((section) => (
              <button
                key={section.id}
                onClick={() => scrollToSection(section.id)}
                className="px-4 py-2 bg-gray-100 hover:bg-purple-100 text-gray-700 hover:text-purple-700 text-xs font-bold rounded-xl whitespace-nowrap transition-colors"
              >
                {isArabic ? section.name : section.nameEn}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="max-w-7xl mx-auto px-4">
        {/* ═════════════════════════════════════════════════════════ */}
        {/* المنتدى */}
        {/* ═════════════════════════════════════════════════════════ */}
        <Section
          id="forum"
          title="💬 المنتدى"
          titleEn="💬 Forum"
          icon={MessageCircle}
          iconColor="bg-violet-500"
          isArabic={isArabic}
        >
          {/* أقسام المنتدى */}
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-6 gap-3 mb-6">
            {forumCategories.map((cat) => {
              const Icon = cat.icon;
              return (
                <div key={cat.id} className="p-3 bg-white rounded-xl border border-gray-100 hover:border-violet-200 hover:shadow-md transition-all cursor-pointer group">
                  <div className={`w-10 h-10 ${cat.color} rounded-xl flex items-center justify-center mb-2 group-hover:scale-110 transition-transform`}>
                    <Icon className="w-5 h-5 text-white" />
                  </div>
                  <h3 className="font-bold text-gray-900 text-xs mb-1">
                    {isArabic ? cat.name : cat.nameEn}
                  </h3>
                  <p className="text-[10px] text-gray-500 line-clamp-2">
                    {isArabic ? cat.description : cat.descriptionEn}
                  </p>
                  <div className="flex items-center gap-2 mt-2 text-[10px] text-gray-400">
                    <span>{cat.topics} {isArabic ? 'موضوع' : 'topics'}</span>
                  </div>
                </div>
              );
            })}
          </div>

          {/* آخر المواضيع */}
          <div className="flex items-center justify-between mb-3">
            <h3 className="font-bold text-gray-900">
              {isArabic ? '🔥 آخر المواضيع' : '🔥 Latest Topics'}
            </h3>
            <button className="flex items-center gap-1 px-3 py-1.5 bg-violet-100 text-violet-600 rounded-lg text-xs font-bold hover:bg-violet-200 transition-colors">
              <Plus className="w-4 h-4" />
              {isArabic ? 'موضوع جديد' : 'New Topic'}
            </button>
          </div>
          <div className="space-y-2">
            {forumTopics.map((topic) => (
              <TopicItem key={topic.id} topic={topic} isArabic={isArabic} />
            ))}
          </div>
        </Section>

        {/* ═════════════════════════════════════════════════════════ */}
        {/* المؤسسات الخيرية */}
        {/* ═════════════════════════════════════════════════════════ */}
        <Section
          id="charities"
          title="💝 المؤسسات الخيرية"
          titleEn="💝 Charity Organizations"
          icon={HandHeart}
          iconColor="bg-rose-500"
          isArabic={isArabic}
        >
          <div className="relative">
            <button onClick={() => scroll('left')} className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center hover:bg-gray-50">
              <ChevronRight className="w-4 h-4" />
            </button>
            <button onClick={() => scroll('right')} className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center hover:bg-gray-50">
              <ChevronLeft className="w-4 h-4" />
            </button>
            <div ref={scrollRef} className="flex gap-3 overflow-x-auto pb-2 scrollbar-hide" style={{ scrollbarWidth: 'none' }}>
              {charities.map((charity) => (
                <Card key={charity.id} item={charity} isArabic={isArabic} type="charity" />
              ))}
            </div>
          </div>
        </Section>

        {/* ═════════════════════════════════════════════════════════ */}
        {/* الفعاليات */}
        {/* ═════════════════════════════════════════════════════════ */}
        <Section
          id="events"
          title="📅 الفعاليات والأنشطة"
          titleEn="📅 Events & Activities"
          icon={Calendar}
          iconColor="bg-blue-500"
          isArabic={isArabic}
        >
          {/* فلترة */}
          <div className="flex gap-2 mb-4 overflow-x-auto pb-2 scrollbar-hide">
            {[
              { id: 'all', name: 'الكل', nameEn: 'All' },
              { id: 'خيري', name: 'خيري', nameEn: 'Charity' },
              { id: 'تعليمي', name: 'تعليمي', nameEn: 'Education' },
              { id: 'رياضي', name: 'رياضي', nameEn: 'Sports' },
              { id: 'اجتماعي', name: 'اجتماعي', nameEn: 'Social' },
            ].map((filter) => (
              <button
                key={filter.id}
                onClick={() => setActiveTab(filter.id)}
                className={`px-4 py-2 rounded-xl text-xs font-bold transition-colors ${
                  activeTab === filter.id ? 'bg-blue-500 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                }`}
              >
                {isArabic ? filter.name : filter.nameEn}
              </button>
            ))}
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-4">
            {events
              .filter((e) => activeTab === 'all' || e.category === activeTab)
              .map((event) => (
                <Card key={event.id} item={event} isArabic={isArabic} type="event" />
              ))}
          </div>
        </Section>

        {/* ═════════════════════════════════════════════════════════ */}
        {/* الأخبار المحلية */}
        {/* ═════════════════════════════════════════════════════════ */}
        <Section
          id="news"
          title="📰 الأخبار المحلية"
          titleEn="📰 Local News"
          icon={Flame}
          iconColor="bg-orange-500"
          isArabic={isArabic}
        >
          <div className="space-y-3">
            {localNews.map((news) => (
              <div key={news.id} className="flex gap-3 p-3 bg-white rounded-xl border border-gray-100 hover:border-orange-200 hover:shadow-md transition-all cursor-pointer">
                <img src={news.image} alt="" className="w-24 h-24 object-cover rounded-lg flex-shrink-0" />
                <div className="flex-1">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="px-2 py-0.5 bg-orange-100 text-orange-600 text-[10px] font-bold rounded-full">
                      {isArabic ? news.category : news.categoryEn}
                    </span>
                    <span className="text-[10px] text-gray-400">{news.date}</span>
                  </div>
                  <h3 className="font-bold text-gray-900 text-sm mb-1">
                    {isArabic ? news.title : news.titleEn}
                  </h3>
                  <p className="text-xs text-gray-500 line-clamp-2">
                    {isArabic ? news.summary : news.summaryEn}
                  </p>
                  <div className="flex items-center gap-2 mt-2 text-[10px] text-gray-400">
                    <Eye className="w-3 h-3" />
                    <span>{news.views}</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </Section>

        {/* ═════════════════════════════════════════════════════════ */}
        {/* فرص التطوع */}
        {/* ═════════════════════════════════════════════════════════ */}
        <Section
          id="volunteer"
          title="🤝 فرص التطوع"
          titleEn="🤝 Volunteer Opportunities"
          icon={HandHeart}
          iconColor="bg-emerald-500"
          isArabic={isArabic}
        >
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {volunteerOpportunities.map((opp) => (
              <div key={opp.id} className="p-4 bg-white rounded-xl border border-gray-100 hover:border-emerald-200 hover:shadow-md transition-all cursor-pointer">
                <div className="flex items-start justify-between mb-2">
                  <div>
                    <h3 className="font-bold text-gray-900 text-sm mb-1">
                      {isArabic ? opp.title : opp.titleEn}
                    </h3>
                    <p className="text-xs text-gray-500">
                      {isArabic ? opp.organization : opp.organizationEn}
                    </p>
                  </div>
                  <span className="px-2 py-1 bg-emerald-100 text-emerald-600 text-[10px] font-bold rounded-full">
                    {opp.registered}/{opp.spots}
                  </span>
                </div>
                <div className="flex items-center gap-4 text-xs text-gray-500">
                  <div className="flex items-center gap-1">
                    <Calendar className="w-3.5 h-3.5" />
                    <span>{opp.date}</span>
                  </div>
                  <div className="flex items-center gap-1">
                    <Clock className="w-3.5 h-3.5" />
                    <span>{opp.time}</span>
                  </div>
                  <div className="flex items-center gap-1">
                    <MapPin className="w-3.5 h-3.5" />
                    <span>{opp.location}</span>
                  </div>
                </div>
                <button className="w-full mt-3 py-2 bg-emerald-500 text-white rounded-lg text-xs font-bold hover:bg-emerald-600 transition-colors">
                  {isArabic ? 'سجل الآن' : 'Register Now'}
                </button>
              </div>
            ))}
          </div>
        </Section>
      </div>

      {/* Footer CTA */}
      <div className="sticky bottom-0 bg-gradient-to-r from-purple-600 to-violet-600 p-4 shadow-lg">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <span className="text-white font-bold">
            {isArabic ? 'شارك في المجتمع!' : 'Join the community!'}
          </span>
          <button className="flex items-center gap-2 px-6 py-3 bg-white text-purple-600 rounded-xl font-bold hover:bg-gray-100 transition-colors">
            <Plus className="w-5 h-5" />
            {isArabic ? 'مشاركة جديدة' : 'New Post'}
          </button>
        </div>
      </div>
    </main>
  );
}
