'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Briefcase, Heart, Star, ChevronLeft, ChevronRight, BriefcaseBusiness, Code, Stethoscope, GraduationCap, Building, Building2, UserCircle, Grid3X3 } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

// ==================== أنواع البيانات ====================

interface Job {
  id: string;
  title: string;
  titleEn: string;
  company: string;
  companyEn: string;
  location: string;
  type: 'full-time' | 'part-time' | 'remote';
  typeAr: string;
  typeEn: string;
  salary: string;
  posted: string;
  urgent?: boolean;
  featured?: boolean;
  category: 'tech' | 'medical' | 'education' | 'sales' | 'admin' | 'other';
  categoryAr: string;
  categoryEn: string;
  rating: number;
  reviews: number;
  images: string[];
  description?: string;
  descriptionEn?: string;
}

interface JobSeeker {
  id: string;
  name: string;
  nameEn: string;
  title: string;
  titleEn: string;
  category: 'tech' | 'medical' | 'education' | 'sales' | 'admin' | 'other';
  categoryAr: string;
  categoryEn: string;
  experience: string;
  experienceEn: string;
  skills: string[];
  skillsEn: string[];
  location: string;
  available: boolean;
  image: string;
  rating: number;
  featured?: boolean;
  phone: string;
  email?: string;
  cv?: string;
}

// ==================== بيانات الوظائف الشاغرة ====================

const qudsayaCenterJobs: Job[] = [
  {
    id: '1',
    title: 'مهندس برمجيات',
    titleEn: 'Software Engineer',
    company: 'شركة تقنية المعلومات',
    companyEn: 'IT Company',
    location: 'قدسيا - المركز',
    type: 'full-time',
    typeAr: 'دوام كامل',
    typeEn: 'Full-time',
    salary: '500-800$',
    posted: 'منذ يوم',
    featured: true,
    category: 'tech',
    categoryAr: 'تقنية',
    categoryEn: 'Tech',
    rating: 4.8,
    reviews: 12,
    images: [
      'https://images.unsplash.com/photo-1497366216548-37526070297c?auto=format&fit=crop&w=300&q=80',
    ],
    description: 'مطلوب مهندس برمجيات بخبرة 3+ سنوات',
    descriptionEn: 'Software engineer with 3+ years experience required'
  },
  {
    id: '2',
    title: 'محاسب',
    titleEn: 'Accountant',
    company: 'مؤسسة تجارية',
    companyEn: 'Trading Company',
    location: 'قدسيا',
    type: 'full-time',
    typeAr: 'دوام كامل',
    typeEn: 'Full-time',
    salary: '300-450$',
    posted: 'منذ يومين',
    category: 'admin',
    categoryAr: 'إدارة',
    categoryEn: 'Admin',
    rating: 4.5,
    reviews: 8,
    images: [
      'https://images.unsplash.com/photo-1554224155-8d04cb21cd6c?auto=format&fit=crop&w=300&q=80',
    ]
  },
  {
    id: '3',
    title: 'معلم لغة إنجليزية',
    titleEn: 'English Teacher',
    company: 'معهد لغات',
    companyEn: 'Language Institute',
    location: 'قدسيا',
    type: 'part-time',
    typeAr: 'دوام جزئي',
    typeEn: 'Part-time',
    salary: '200-350$',
    posted: 'منذ 3 أيام',
    urgent: true,
    category: 'education',
    categoryAr: 'تعليم',
    categoryEn: 'Education',
    rating: 4.7,
    reviews: 15,
    images: [
      'https://images.unsplash.com/photo-1509062522246-3755977927d7?auto=format&fit=crop&w=300&q=80',
    ]
  },
  {
    id: '4',
    title: 'مصمم جرافيك',
    titleEn: 'Graphic Designer',
    company: 'وكالة إعلانات',
    companyEn: 'Advertising Agency',
    location: 'قدسيا',
    type: 'remote',
    typeAr: 'عن بعد',
    typeEn: 'Remote',
    salary: '250-400$',
    posted: 'منذ أسبوع',
    category: 'tech',
    categoryAr: 'تقنية',
    categoryEn: 'Tech',
    rating: 4.6,
    reviews: 6,
    images: [
      'https://images.unsplash.com/photo-1561070791-2526d30994b5?auto=format&fit=crop&w=300&q=80',
    ]
  },
  {
    id: '5',
    title: 'طبيب أسنان',
    titleEn: 'Dentist',
    company: 'عيادة خاصة',
    companyEn: 'Private Clinic',
    location: 'قدسيا',
    type: 'full-time',
    typeAr: 'دوام كامل',
    typeEn: 'Full-time',
    salary: 'حسب الخبرة',
    posted: 'منذ 5 أيام',
    featured: true,
    category: 'medical',
    categoryAr: 'طبي',
    categoryEn: 'Medical',
    rating: 4.9,
    reviews: 22,
    images: [
      'https://images.unsplash.com/photo-1629909613654-28e377c37b09?auto=format&fit=crop&w=300&q=80',
    ]
  },
  {
    id: '6',
    title: 'سائق توصيل',
    titleEn: 'Delivery Driver',
    company: 'مطعم',
    companyEn: 'Restaurant',
    location: 'قدسيا',
    type: 'full-time',
    typeAr: 'دوام كامل',
    typeEn: 'Full-time',
    salary: '150$ + نسبة',
    posted: 'منذ ساعتين',
    urgent: true,
    category: 'other',
    categoryAr: 'أخرى',
    categoryEn: 'Other',
    rating: 4.3,
    reviews: 5,
    images: [
      'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?auto=format&fit=crop&w=300&q=80',
    ]
  }
];

const qudsayaDahiaJobs: Job[] = [
  {
    id: '1',
    title: 'مهندس مدني',
    titleEn: 'Civil Engineer',
    company: 'شركة مقاولات',
    companyEn: 'Construction Company',
    location: 'الضاحية',
    type: 'full-time',
    typeAr: 'دوام كامل',
    typeEn: 'Full-time',
    salary: '600-900$',
    posted: 'منذ يوم',
    featured: true,
    category: 'tech',
    categoryAr: 'تقنية',
    categoryEn: 'Tech',
    rating: 4.7,
    reviews: 14,
    images: [
      'https://images.unsplash.com/photo-1504307651254-35680f356dfd?auto=format&fit=crop&w=300&q=80',
    ]
  },
  {
    id: '2',
    title: 'محاسب',
    titleEn: 'Accountant',
    company: 'مؤسسة تجارية',
    companyEn: 'Trading Company',
    location: 'الضاحية',
    type: 'full-time',
    typeAr: 'دوام كامل',
    typeEn: 'Full-time',
    salary: '350-500$',
    posted: 'منذ يومين',
    category: 'admin',
    categoryAr: 'إدارة',
    categoryEn: 'Admin',
    rating: 4.4,
    reviews: 7,
    images: [
      'https://images.unsplash.com/photo-1554224155-8d04cb21cd6c?auto=format&fit=crop&w=300&q=80',
    ]
  },
  {
    id: '3',
    title: 'معلم رياضيات',
    titleEn: 'Math Teacher',
    company: 'مدرسة خاصة',
    companyEn: 'Private School',
    location: 'الضاحية',
    type: 'full-time',
    typeAr: 'دوام كامل',
    typeEn: 'Full-time',
    salary: '300-450$',
    posted: 'منذ 4 أيام',
    category: 'education',
    categoryAr: 'تعليم',
    categoryEn: 'Education',
    rating: 4.6,
    reviews: 11,
    images: [
      'https://images.unsplash.com/photo-1509062522246-3755977927d7?auto=format&fit=crop&w=300&q=80',
    ]
  },
  {
    id: '4',
    title: 'مدير مبيعات',
    titleEn: 'Sales Manager',
    company: 'شركة تجارية',
    companyEn: 'Trading Company',
    location: 'الضاحية',
    type: 'full-time',
    typeAr: 'دوام كامل',
    typeEn: 'Full-time',
    salary: '400-600$ + عمولات',
    posted: 'منذ يومين',
    category: 'sales',
    categoryAr: 'مبيعات',
    categoryEn: 'Sales',
    rating: 4.5,
    reviews: 9,
    images: [
      'https://images.unsplash.com/photo-1552664730-d307ca884978?auto=format&fit=crop&w=300&q=80',
    ]
  },
  {
    id: '5',
    title: 'صيدلي',
    titleEn: 'Pharmacist',
    company: 'صيدلية',
    companyEn: 'Pharmacy',
    location: 'الضاحية',
    type: 'full-time',
    typeAr: 'دوام كامل',
    typeEn: 'Full-time',
    salary: '400-600$',
    posted: 'منذ 3 أيام',
    featured: true,
    category: 'medical',
    categoryAr: 'طبي',
    categoryEn: 'Medical',
    rating: 4.8,
    reviews: 16,
    images: [
      'https://images.unsplash.com/photo-1576091160550-2173dba999ef?auto=format&fit=crop&w=300&q=80',
    ]
  },
  {
    id: '6',
    title: 'سائق توصيل',
    titleEn: 'Delivery Driver',
    company: 'سوبر ماركت',
    companyEn: 'Supermarket',
    location: 'الضاحية',
    type: 'full-time',
    typeAr: 'دوام كامل',
    typeEn: 'Full-time',
    salary: '180$ + نسبة',
    posted: 'منذ ساعة',
    urgent: true,
    category: 'other',
    categoryAr: 'أخرى',
    categoryEn: 'Other',
    rating: 4.2,
    reviews: 4,
    images: [
      'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?auto=format&fit=crop&w=300&q=80',
    ]
  }
];

// ==================== بيانات الباحثين عن عمل ====================

const qudsayaCenterSeekers: JobSeeker[] = [
  {
    id: 's1',
    name: 'أحمد محمد',
    nameEn: 'Ahmed Mohammed',
    title: 'مطور برمجيات',
    titleEn: 'Software Developer',
    category: 'tech',
    categoryAr: 'تقنية',
    categoryEn: 'Tech',
    experience: '5 سنوات',
    experienceEn: '5 years',
    skills: ['React', 'Node.js', 'Python', 'SQL'],
    skillsEn: ['React', 'Node.js', 'Python', 'SQL'],
    location: 'قدسيا',
    available: true,
    image: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?auto=format&fit=crop&w=200&q=80',
    rating: 4.9,
    featured: true,
    phone: '0999123456',
    email: 'ahmed@email.com'
  },
  {
    id: 's2',
    name: 'سارة أحمد',
    nameEn: 'Sara Ahmed',
    title: 'محاسبة',
    titleEn: 'Accountant',
    category: 'admin',
    categoryAr: 'إدارة',
    categoryEn: 'Admin',
    experience: '3 سنوات',
    experienceEn: '3 years',
    skills: ['Excel', 'QuickBooks', 'SAP', 'Tally'],
    skillsEn: ['Excel', 'QuickBooks', 'SAP', 'Tally'],
    location: 'قدسيا',
    available: true,
    image: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=200&q=80',
    rating: 4.7,
    phone: '0999234567',
    email: 'sara@email.com'
  },
  {
    id: 's3',
    name: 'محمد علي',
    nameEn: 'Mohammed Ali',
    title: 'معلم لغة إنجليزية',
    titleEn: 'English Teacher',
    category: 'education',
    categoryAr: 'تعليم',
    categoryEn: 'Education',
    experience: '7 سنوات',
    experienceEn: '7 years',
    skills: ['Teaching', 'IELTS', 'TOEFL', 'Curriculum Design'],
    skillsEn: ['Teaching', 'IELTS', 'TOEFL', 'Curriculum Design'],
    location: 'قدسيا - المركز',
    available: true,
    image: 'https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?auto=format&fit=crop&w=200&q=80',
    rating: 4.8,
    featured: true,
    phone: '0999345678'
  },
  {
    id: 's4',
    name: 'فاطمة حسن',
    nameEn: 'Fatima Hassan',
    title: 'مصممة جرافيك',
    titleEn: 'Graphic Designer',
    category: 'tech',
    categoryAr: 'تقنية',
    categoryEn: 'Tech',
    experience: '4 سنوات',
    experienceEn: '4 years',
    skills: ['Photoshop', 'Illustrator', 'Figma', 'UI/UX'],
    skillsEn: ['Photoshop', 'Illustrator', 'Figma', 'UI/UX'],
    location: 'قدسيا',
    available: true,
    image: 'https://images.unsplash.com/photo-1438761681033-6461ffad8d80?auto=format&fit=crop&w=200&q=80',
    rating: 4.6,
    phone: '0999456789'
  },
  {
    id: 's5',
    name: 'خالد عمر',
    nameEn: 'Khaled Omar',
    title: 'طبيب أسنان',
    titleEn: 'Dentist',
    category: 'medical',
    categoryAr: 'طبي',
    categoryEn: 'Medical',
    experience: '10 سنوات',
    experienceEn: '10 years',
    skills: ['Orthodontics', 'Oral Surgery', 'Cosmetic Dentistry'],
    skillsEn: ['Orthodontics', 'Oral Surgery', 'Cosmetic Dentistry'],
    location: 'قدسيا',
    available: false,
    image: 'https://images.unsplash.com/photo-1612349317150-e413f6a5b16d?auto=format&fit=crop&w=200&q=80',
    rating: 4.9,
    featured: true,
    phone: '0999567890'
  },
  {
    id: 's6',
    name: 'نور الدين',
    nameEn: 'Nour Aldeen',
    title: 'سائق توصيل',
    titleEn: 'Delivery Driver',
    category: 'other',
    categoryAr: 'أخرى',
    categoryEn: 'Other',
    experience: '2 سنوات',
    experienceEn: '2 years',
    skills: ['قيادة', 'معرفة المنطقة', 'التواصل الجيد'],
    skillsEn: ['Driving', 'Area Knowledge', 'Good Communication'],
    location: 'قدسيا',
    available: true,
    image: 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?auto=format&fit=crop&w=200&q=80',
    rating: 4.5,
    phone: '0999678901'
  }
];

const qudsayaDahiaSeekers: JobSeeker[] = [
  {
    id: 's1',
    name: 'عمر خالد',
    nameEn: 'Omar Khaled',
    title: 'مهندس مدني',
    titleEn: 'Civil Engineer',
    category: 'tech',
    categoryAr: 'تقنية',
    categoryEn: 'Tech',
    experience: '6 سنوات',
    experienceEn: '6 years',
    skills: ['AutoCAD', 'Project Management', 'Structural Design'],
    skillsEn: ['AutoCAD', 'Project Management', 'Structural Design'],
    location: 'الضاحية',
    available: true,
    image: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?auto=format&fit=crop&w=200&q=80',
    rating: 4.8,
    featured: true,
    phone: '0999789012'
  },
  {
    id: 's2',
    name: 'ليلى محمد',
    nameEn: 'Layla Mohammed',
    title: 'محاسبة',
    titleEn: 'Accountant',
    category: 'admin',
    categoryAr: 'إدارة',
    categoryEn: 'Admin',
    experience: '4 سنوات',
    experienceEn: '4 years',
    skills: ['Excel', 'QuickBooks', 'Financial Reporting'],
    skillsEn: ['Excel', 'QuickBooks', 'Financial Reporting'],
    location: 'الضاحية',
    available: true,
    image: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=200&q=80',
    rating: 4.6,
    phone: '0999890123'
  },
  {
    id: 's3',
    name: 'حسن يوسف',
    nameEn: 'Hassan Youssef',
    title: 'معلم رياضيات',
    titleEn: 'Math Teacher',
    category: 'education',
    categoryAr: 'تعليم',
    categoryEn: 'Education',
    experience: '8 سنوات',
    experienceEn: '8 years',
    skills: ['Teaching', 'Curriculum Development', 'Student Engagement'],
    skillsEn: ['Teaching', 'Curriculum Development', 'Student Engagement'],
    location: 'الضاحية',
    available: true,
    image: 'https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?auto=format&fit=crop&w=200&q=80',
    rating: 4.7,
    featured: true,
    phone: '0999901234'
  },
  {
    id: 's4',
    name: 'رنا أحمد',
    nameEn: 'Rana Ahmed',
    title: 'مديرة مبيعات',
    titleEn: 'Sales Manager',
    category: 'sales',
    categoryAr: 'مبيعات',
    categoryEn: 'Sales',
    experience: '5 سنوات',
    experienceEn: '5 years',
    skills: ['Sales Strategy', 'Team Leadership', 'CRM'],
    skillsEn: ['Sales Strategy', 'Team Leadership', 'CRM'],
    location: 'الضاحية - المركز التجاري',
    available: true,
    image: 'https://images.unsplash.com/photo-1438761681033-6461ffad8d80?auto=format&fit=crop&w=200&q=80',
    rating: 4.8,
    phone: '0999012345'
  },
  {
    id: 's5',
    name: 'سمير حسين',
    nameEn: 'Samir Hussein',
    title: 'صيدلي',
    titleEn: 'Pharmacist',
    category: 'medical',
    categoryAr: 'طبي',
    categoryEn: 'Medical',
    experience: '3 سنوات',
    experienceEn: '3 years',
    skills: ['Pharmaceutical Care', 'Patient Counseling', 'Inventory Management'],
    skillsEn: ['Pharmaceutical Care', 'Patient Counseling', 'Inventory Management'],
    location: 'الضاحية',
    available: true,
    image: 'https://images.unsplash.com/photo-1612349317150-e413f6a5b16d?auto=format&fit=crop&w=200&q=80',
    rating: 4.5,
    phone: '0999123456'
  },
  {
    id: 's6',
    name: 'ياسر محمود',
    nameEn: 'Yasser Mahmoud',
    title: 'سائق',
    titleEn: 'Driver',
    category: 'other',
    categoryAr: 'أخرى',
    categoryEn: 'Other',
    experience: '3 سنوات',
    experienceEn: '3 years',
    skills: ['قيادة', 'معرفة الطرق', 'التواصل'],
    skillsEn: ['Driving', 'Road Knowledge', 'Communication'],
    location: 'الضاحية',
    available: true,
    image: 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?auto=format&fit=crop&w=200&q=80',
    rating: 4.4,
    phone: '0999234567'
  }
];

// ==================== البيانات حسب المنطقة ====================

const jobsByRegion: Record<Region, Job[]> = {
  'qudsaya-center': qudsayaCenterJobs,
  'qudsaya-dahia': qudsayaDahiaJobs
};

const seekersByRegion: Record<Region, JobSeeker[]> = {
  'qudsaya-center': qudsayaCenterSeekers,
  'qudsaya-dahia': qudsayaDahiaSeekers
};

// ==================== الفلاتر ====================

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Briefcase },
  { id: 'tech', name: 'تقنية', nameEn: 'Tech', icon: Code },
  { id: 'medical', name: 'طبي', nameEn: 'Medical', icon: Stethoscope },
  { id: 'education', name: 'تعليم', nameEn: 'Education', icon: GraduationCap },
  { id: 'admin', name: 'إدارة', nameEn: 'Admin', icon: Building },
  { id: 'sales', name: 'مبيعات', nameEn: 'Sales', icon: BriefcaseBusiness },
];

const typeStyles = {
  'full-time': { bg: 'bg-blue-100', text: 'text-blue-700' },
  'part-time': { bg: 'bg-purple-100', text: 'text-purple-700' },
  'remote': { bg: 'bg-teal-100', text: 'text-teal-700' }
};

// ==================== المكون الرئيسي ====================

export default function Jobs() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const jobs = jobsByRegion[region];
  const seekers = seekersByRegion[region];
  
  // التبويب النشط: 'jobs' أو 'seekers'
  const [activeTab, setActiveTab] = useState<'jobs' | 'seekers'>('jobs');
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  // تصفية البيانات
  const filteredJobs = jobs.filter(job => activeCategory === 'all' || job.category === activeCategory);
  const filteredSeekers = seekers.filter(seeker => activeCategory === 'all' || seeker.category === activeCategory);
  const currentData = activeTab === 'jobs' ? filteredJobs : filteredSeekers;

  // فحص التمرير
  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  useEffect(() => {
    checkScroll();
    const scrollEl = scrollRef.current;
    if (scrollEl) {
      scrollEl.addEventListener('scroll', checkScroll);
      return () => scrollEl.removeEventListener('scroll', checkScroll);
    }
  }, [currentData]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({
        left: direction === 'left' ? -300 : 300,
        behavior: 'smooth'
      });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => 
      prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]
    );
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          <button
            onClick={() => setActiveTab('jobs')}
            className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
              activeTab === 'jobs'
                ? 'border-gray-900 text-gray-900' 
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
            }`}
          >
            <Building2 className="w-5 h-5" />
            <span className="text-xs font-medium">{isArabic ? 'وظائف' : 'Jobs'}</span>
          </button>
          <button
            onClick={() => setActiveTab('seekers')}
            className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
              activeTab === 'seekers'
                ? 'border-gray-900 text-gray-900' 
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
            }`}
          >
            <UserCircle className="w-5 h-5" />
            <span className="text-xs font-medium">{isArabic ? 'باحثون عن عمل' : 'Seekers'}</span>
          </button>
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
            
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCategory(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive 
                    ? 'border-gray-900 text-gray-900' 
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        {/* Horizontal Scrolling Container - Airbnb Style */}
        <div className="relative">
          {/* Navigation Buttons */}
          {canScrollLeft && (
            <button
              onClick={() => scroll('left')}
              className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button
              onClick={() => scroll('right')}
              className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          {/* Scrollable Cards */}
          <div
            ref={scrollRef}
            className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide"
            style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
          >
            {activeTab === 'jobs' ? (
              // ==================== بطاقات الوظائف ====================
              filteredJobs.slice(0, 8).map((job) => {
                const isFavorite = favorites.includes(job.id);
                const styles = typeStyles[job.type];
                
                return (
                  <div
                    key={job.id}
                    className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                  >
                    {/* Image Container - Airbnb Style */}
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img 
                        src={job.images[0]} 
                        alt="" 
                        className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                      />
                      
                      {/* Favorite Button */}
                      <button
                        onClick={(e) => { e.stopPropagation(); toggleFavorite(job.id); }}
                        className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                      >
                        <Heart 
                          className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} 
                        />
                      </button>

                      {/* Type Badge */}
                      <div className="absolute top-2 left-2">
                        <span className={`px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${styles.bg} ${styles.text}`}>
                          {isArabic ? job.typeAr : job.typeEn}
                        </span>
                      </div>

                      {/* Featured/Urgent Badge */}
                      {(job.featured || job.urgent) && (
                        <div className="absolute bottom-2 left-2">
                          <span className={`px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                            job.featured ? 'bg-amber-500/90 text-white' : 'bg-red-500/90 text-white'
                          }`}>
                            {job.featured ? (isArabic ? 'مميز' : 'Featured') : (isArabic ? 'عاجل' : 'Urgent')}
                          </span>
                        </div>
                      )}
                    </div>

                    {/* Job Info - Airbnb Style */}
                    <div>
                      {/* Company & Rating */}
                      <div className="flex items-center justify-between mb-0.5">
                        <span className="text-xs text-gray-500 font-medium truncate flex-1">
                          {isArabic ? job.company : job.companyEn}
                        </span>
                        <div className="flex items-center gap-0.5 flex-shrink-0">
                          <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                          <span className="text-xs font-medium">{job.rating}</span>
                        </div>
                      </div>

                      {/* Title */}
                      <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-2">
                        {isArabic ? job.title : job.titleEn}
                      </h3>

                      {/* Location */}
                      <p className="text-xs text-gray-500 mb-1 truncate">
                        📍 {job.location}
                      </p>

                      {/* Salary */}
                      <div className="flex items-baseline gap-0.5">
                        <span className="text-sm font-bold text-gray-900">$</span>
                        <span className="text-sm font-bold text-gray-900">{job.salary}</span>
                      </div>
                    </div>
                  </div>
                );
              })
            ) : (
              // ==================== بطاقات الباحثين عن عمل ====================
              filteredSeekers.slice(0, 8).map((seeker) => {
                const isFavorite = favorites.includes(seeker.id);
                
                return (
                  <div
                    key={seeker.id}
                    className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                  >
                    {/* Image Container - Airbnb Style */}
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img 
                        src={seeker.image} 
                        alt="" 
                        className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                      />
                      
                      {/* Favorite Button */}
                      <button
                        onClick={(e) => { e.stopPropagation(); toggleFavorite(seeker.id); }}
                        className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                      >
                        <Heart 
                          className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} 
                        />
                      </button>

                      {/* Available Badge */}
                      <div className="absolute top-2 left-2">
                        <span className={`px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                          seeker.available ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'
                        }`}>
                          {seeker.available ? (isArabic ? 'متاح' : 'Available') : (isArabic ? 'غير متاح' : 'Unavailable')}
                        </span>
                      </div>

                      {/* Featured Badge */}
                      {seeker.featured && (
                        <div className="absolute bottom-2 left-2">
                          <span className="px-2 py-0.5 text-[10px] font-semibold rounded-full bg-amber-500/90 text-white backdrop-blur-sm">
                            ⭐ {isArabic ? 'مميز' : 'Featured'}
                          </span>
                        </div>
                      )}
                    </div>

                    {/* Seeker Info - Airbnb Style */}
                    <div>
                      {/* Name & Rating */}
                      <div className="flex items-center justify-between mb-0.5">
                        <h3 className="text-sm font-medium text-gray-900 truncate flex-1">
                          {isArabic ? seeker.name : seeker.nameEn}
                        </h3>
                        <div className="flex items-center gap-0.5 flex-shrink-0">
                          <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                          <span className="text-xs font-medium">{seeker.rating}</span>
                        </div>
                      </div>

                      {/* Title */}
                      <p className="text-xs text-emerald-600 font-medium mb-1">
                        {isArabic ? seeker.title : seeker.titleEn}
                      </p>

                      {/* Experience & Location */}
                      <p className="text-xs text-gray-500 mb-1 truncate">
                        {isArabic ? seeker.experience : seeker.experienceEn} • {seeker.location}
                      </p>

                      {/* Phone */}
                      <a
                        href={`tel:${seeker.phone}`}
                        onClick={(e) => e.stopPropagation()}
                        className="text-xs font-bold text-blue-600 hover:underline"
                      >
                        📞 {seeker.phone}
                      </a>
                    </div>
                  </div>
                );
              })
            )}
            
            {/* بطاقة عرض الكل */}
            {currentData.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors flex items-center justify-center bg-gray-50">
                  <div className="text-center">
                    <Grid3X3 className="w-12 h-12 text-gray-400 mx-auto mb-2" />
                    <span className="text-lg font-bold text-gray-700">+{currentData.length - 8}</span>
                    <p className="text-sm text-gray-500">{isArabic ? 'عرض الكل' : 'View All'}</p>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح الكل' : 'Browse all'}</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع الوظائف/الباحثين */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              {activeTab === 'jobs' ? (isArabic ? '💼 جميع الوظائف' : '💼 All Jobs') : (isArabic ? '👤 جميع الباحثين عن عمل' : '👤 All Job Seekers')}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {activeTab === 'jobs' ? (
                filteredJobs.map((job) => {
                  const isFavorite = favorites.includes(job.id);
                  const styles = typeStyles[job.type];
                  return (
                    <div key={job.id} className="group cursor-pointer">
                      <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                        <img src={job.images[0]} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                        <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${styles.bg} ${styles.text}`}>
                          {isArabic ? job.typeAr : job.typeEn}
                        </span>
                      </div>
                      <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? job.title : job.titleEn}</h3>
                      <p className="text-xs text-gray-500">{isArabic ? job.company : job.companyEn}</p>
                    </div>
                  );
                })
              ) : (
                filteredSeekers.map((seeker) => {
                  const isFavorite = favorites.includes(seeker.id);
                  return (
                    <div key={seeker.id} className="group cursor-pointer">
                      <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                        <img src={seeker.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                        <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                          seeker.available ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'
                        }`}>
                          {seeker.available ? (isArabic ? 'متاح' : 'Available') : (isArabic ? 'غير متاح' : 'Unavailable')}
                        </span>
                      </div>
                      <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? seeker.name : seeker.nameEn}</h3>
                      <p className="text-xs text-emerald-600">{isArabic ? seeker.title : seeker.titleEn}</p>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </DrawerContent>
      </Drawer>
    </section>
  );
}
